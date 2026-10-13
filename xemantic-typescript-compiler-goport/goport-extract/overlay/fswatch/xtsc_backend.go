// PORT OVERLAY FILE ((TSGO.5-c), docs/goport-cli.md § 2c): ADDED to internal/fswatch by goport-extract
// (go/packages Overlay); it is never written into typescript-go-repo. The port keeps fswatch's
// platform-independent core verbatim — watcher, dirWatch, eventList (coalescing), debounce, the
// callbacks' ignore/recursive filtering — and replaces only the OS layer. xtscBackend is
// inotify_linux.go's inotifyBackend (tag typescript/v7.0.2) with its system calls behind XtscNotifier,
// which the port's runtime implements (JVM: java.nio.file.WatchService; Kotlin/Native: inotify(7)):
// the event loop, the subscription bookkeeping and handleSubscription are inotify_linux.go's own.
package fswatch

import (
	"fmt"
	"sync/atomic"
)

// The inotify(7) event bits XtscNotifier reports (linux/inotify.h values, which every implementation uses).
const (
	XtscInModify     uint32 = 0x00000002
	XtscInMovedFrom  uint32 = 0x00000040
	XtscInMovedTo    uint32 = 0x00000080
	XtscInCreate     uint32 = 0x00000100
	XtscInDelete     uint32 = 0x00000200
	XtscInDeleteSelf uint32 = 0x00000400
	XtscInMoveSelf   uint32 = 0x00000800
	XtscInQOverflow  uint32 = 0x00004000
	XtscInIsDir      uint32 = 0x40000000
)

// XtscNotifyEvent is one inotify_event: the watch descriptor, the event bits and the entry's name
// (empty for an event on the watched directory itself).
type XtscNotifyEvent struct {
	Wd   int
	Mask uint32
	Name string
}

// XtscNotifier is an inotify instance: AddWatch is inotify_add_watch with inotifyMask (adding a
// directory already watched returns its descriptor again), RmWatch inotify_rm_watch, Read waits up to
// timeoutMillis for events and returns what is queued (none on timeout), Close releases the instance.
type XtscNotifier interface {
	AddWatch(path string) (int, error)
	RmWatch(wd int) error
	Read(timeoutMillis int) ([]XtscNotifyEvent, error)
	Close() error
}

// XtscInstall makes the platform-default watcher (Default) an XtscNotifier backend over the notifiers
// newNotifier opens (the port's runtime calls it before running the command line).
func XtscInstall(newNotifier func() (XtscNotifier, error)) {
	inotifyWatcher.mu.Lock()
	inotifyWatcher.factory = func() watcherImpl { return newXtscBackend(newNotifier) }
	inotifyWatcher.mu.Unlock()
}

type xtscSubscription struct {
	path      string
	watchPath string
	dirWatch  *dirWatch
	wd        int
}

type xtscBackend struct {
	watcherBase

	newNotifier   func() (XtscNotifier, error)
	notifier      XtscNotifier
	stopping      atomic.Bool
	subscriptions map[int][]*xtscSubscription // multimap<wd, sub>
	endedSignal   chan struct{}

	watchersTouched map[*dirWatch]struct{}
}

func newXtscBackend(newNotifier func() (XtscNotifier, error)) *xtscBackend {
	b := &xtscBackend{
		newNotifier:     newNotifier,
		subscriptions:   map[int][]*xtscSubscription{},
		endedSignal:     make(chan struct{}),
		watchersTouched: make(map[*dirWatch]struct{}),
	}
	b.watcherBase.init(b)
	return b
}

// start is inotifyBackend.start: the poll(2) loop becomes Read with the same 500 ms timeout, and the
// shutdown pipe the stopping flag it checks between reads.
func (b *xtscBackend) start() error {
	defer func() {
		b.closeNotifier()
		close(b.endedSignal)
	}()
	n, err := b.newNotifier()
	if err != nil {
		return fmt.Errorf("unable to initialize inotify: %w", err)
	}
	b.mu.Lock()
	b.notifier = n
	b.mu.Unlock()

	b.notifyStarted()

	for !b.stopping.Load() {
		events, err := n.Read(500)
		if err != nil {
			return fmt.Errorf("unable to poll: %w", err)
		}
		if b.stopping.Load() {
			break
		}
		if len(events) > 0 {
			b.handleEvents(events)
		}
	}
	return nil
}

func (b *xtscBackend) closeNotifier() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.notifier != nil {
		_ = b.notifier.Close()
		b.notifier = nil
	}
}

func (b *xtscBackend) shutdown() {
	if b.stopping.Swap(true) {
		return
	}
	<-b.endedSignal
}

// subscribe is inotifyBackend.subscribe.
func (b *xtscBackend) subscribe(w *dirWatch) error {
	if !w.recursive {
		if _, err := b.watchDir(w, w.dir, w.physicalDir); err != nil {
			return &dirWatchError{
				err:      fmt.Errorf("inotify_add_watch on '%s' failed: %w", w.dir, err),
				dirWatch: w,
			}
		}
		return nil
	}
	if err := walkDirGeneric(w.physicalDir, true, func(watchPath string, isDir bool) error {
		if !isDir {
			return nil
		}
		path := w.displayPath(watchPath)
		if _, err := b.watchDir(w, path, watchPath); err != nil {
			return &dirWatchError{
				err:      fmt.Errorf("inotify_add_watch on '%s' failed: %w", path, err),
				dirWatch: w,
			}
		}
		return nil
	}); err != nil {
		_ = b.closeWatch(w)
		return err
	}
	return nil
}

// watchDir is inotifyBackend.watchDir.
func (b *xtscBackend) watchDir(w *dirWatch, path string, watchPath string) (int, error) {
	wd, err := b.notifier.AddWatch(watchPath)
	if err != nil {
		return 0, err
	}
	sub := &xtscSubscription{path: path, watchPath: watchPath, dirWatch: w, wd: wd}
	b.subscriptions[wd] = append(b.subscriptions[wd], sub)
	return wd, nil
}

// handleEvents is inotifyBackend.handleEvents over the events one Read returned.
func (b *xtscBackend) handleEvents(events []XtscNotifyEvent) {
	watchersTouched := b.watchersTouched
	for i := range events {
		ev := &events[i]
		if ev.Mask&XtscInQOverflow != 0 {
			b.mu.Lock()
			for _, subs := range b.subscriptions {
				for _, sub := range subs {
					sub.dirWatch.events.setError(ErrOverflow)
					watchersTouched[sub.dirWatch] = struct{}{}
				}
			}
			b.mu.Unlock()
			continue
		}
		b.handleEvent(ev, watchersTouched)
	}
	for w := range watchersTouched {
		w.notify()
	}
	clear(watchersTouched)
}

// handleEvent is inotifyBackend.handleEvent.
func (b *xtscBackend) handleEvent(ev *XtscNotifyEvent, touched map[*dirWatch]struct{}) {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, s := range b.subscriptions[ev.Wd] {
		if b.handleSubscription(ev, s) {
			touched[s.dirWatch] = struct{}{}
		}
	}
}

// handleSubscription is inotifyBackend.handleSubscription.
func (b *xtscBackend) handleSubscription(ev *XtscNotifyEvent, sub *xtscSubscription) bool {
	w := sub.dirWatch
	path := sub.path
	watchPath := sub.watchPath
	isDir := ev.Mask&XtscInIsDir != 0
	if ev.Name != "" {
		path = path + "/" + ev.Name
		watchPath = watchPath + "/" + ev.Name
	}

	switch {
	case ev.Mask&(XtscInCreate|XtscInMovedTo) != 0:
		w.events.create(path)
		if isDir && w.recursive {
			_ = walkDirGeneric(watchPath, true, func(p string, pIsDir bool) error {
				if !pIsDir {
					return nil
				}
				_, _ = b.watchDir(w, w.displayPath(p), p)
				return nil
			})
		}

	case ev.Mask&XtscInModify != 0:
		w.events.update(path)

	case ev.Mask&(XtscInDelete|XtscInDeleteSelf|XtscInMovedFrom|XtscInMoveSelf) != 0:
		isSelfEvent := ev.Mask&(XtscInDeleteSelf|XtscInMoveSelf) != 0
		if isSelfEvent && path != w.dir {
			return false
		}
		if isSelfEvent || isDir {
			for wd, list := range b.subscriptions {
				kept := list[:0]
				for _, s := range list {
					if s.path == path || (len(s.path) > len(path) && s.path[len(path)] == '/' && s.path[:len(path)] == path) {
						continue
					}
					kept = append(kept, s)
				}
				if len(kept) == 0 {
					_ = b.notifier.RmWatch(wd)
					delete(b.subscriptions, wd)
				} else {
					b.subscriptions[wd] = kept
				}
			}
		}
		w.events.remove(path)
		if isSelfEvent && path == w.dir {
			w.events.setError(fmt.Errorf("%w: watched directory removed", ErrWatchTerminated))
		}
	}
	return true
}

// closeWatch is inotifyBackend.closeWatch.
func (b *xtscBackend) closeWatch(w *dirWatch) error {
	var firstErr error
	for wd, list := range b.subscriptions {
		kept := list[:0]
		removedAny := false
		for _, s := range list {
			if s.dirWatch == w {
				removedAny = true
				continue
			}
			kept = append(kept, s)
		}
		if !removedAny {
			continue
		}
		if len(kept) == 0 {
			if b.notifier != nil {
				if err := b.notifier.RmWatch(wd); err != nil && firstErr == nil {
					firstErr = &dirWatchError{
						err:      fmt.Errorf("unable to remove dirWatch: %w", err),
						dirWatch: w,
					}
				}
			}
			delete(b.subscriptions, wd)
		} else {
			b.subscriptions[wd] = kept
		}
	}
	return firstErr
}
