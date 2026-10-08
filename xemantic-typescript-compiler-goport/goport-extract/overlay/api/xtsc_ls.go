// PORT OVERLAY FILE ((TSGO.4-a), docs/goport-ls.md): ADDED to internal/api by goport-extract (go/packages
// Overlay) so the port carries tsgo's LANGUAGE SERVICE behind the language server's request handling; it
// is never written into typescript-go-repo. tsgo's language server (internal/lsp/server.go) runs the
// project system (internal/project: snapshots, configured and inferred projects, file watching) and
// dispatches each JSON-RPC request to a handler that calls one ls.LanguageService method. The port keeps
// the language service whole and replaces the server's two ends: the caller hands it ONE configured
// project (the hand-written `project` shim over a program built by XtscOpenProgram), and XtscLSRequest is
// the server's handler table for the requests it serves, each handler's body as server.go writes it.
package api

import (
	"context"
	"errors"
	"fmt"
	"iter"

	"github.com/microsoft/typescript-go/internal/collections"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/json"
	"github.com/microsoft/typescript-go/internal/ls"
	"github.com/microsoft/typescript-go/internal/ls/lsutil"
	"github.com/microsoft/typescript-go/internal/lsp/lsproto"
	"github.com/microsoft/typescript-go/internal/project"
	"github.com/microsoft/typescript-go/internal/tspath"
)

// XtscResolveClientCapabilities is handleInitialize's capability resolution: the client's
// `initialize` params (JSON) unmarshalled as the server does (lsproto.InitializeParams) and resolved.
func XtscResolveClientCapabilities(initializeParams json.Value) (*lsproto.ResolvedClientCapabilities, error) {
	var params lsproto.InitializeParams
	if err := json.Unmarshal(initializeParams, &params); err != nil {
		return nil, err
	}
	caps := params.Capabilities.Resolve()
	return &caps, nil
}

// XtscUserPreferences is RequestConfiguration's parse of the client's `workspace/configuration` answer
// for the sections js/ts, typescript, javascript and editor (in that order, a JSON array); an empty
// value is the defaults (a client without the configuration capability and no initialization options).
func XtscUserPreferences(configurations json.Value) (lsutil.UserPreferences, error) {
	if len(configurations) == 0 {
		return lsutil.NewDefaultUserPreferences(), nil
	}
	var configs []any
	if err := json.Unmarshal(configurations, &configs); err != nil {
		return lsutil.UserPreferences{}, err
	}
	configMap := map[string]any{}
	for i, config := range configs {
		switch i {
		case 0:
			configMap["js/ts"] = config
		case 1:
			configMap["typescript"] = config
		case 2:
			configMap["javascript"] = config
		case 3:
			configMap["editor"] = config
		}
	}
	return lsutil.ParseUserPreferences(configMap), nil
}

// XtscLanguageService is one configured project's language service (Session.GetLanguageService:
// ls.NewLanguageService over the project's program and the snapshot as the host).
type XtscLanguageService struct {
	Snapshot *project.Snapshot
	Project  *project.Project
	Caps     *lsproto.ResolvedClientCapabilities
}

func (x *XtscLanguageService) languageService(uri lsproto.DocumentUri) *ls.LanguageService {
	return ls.NewLanguageService(x.Project.Id(), x.Project.GetProgram(), x.Snapshot, uri.FileName())
}

// XtscLSRequest answers one language-server request: params are the request's JSON params, the result
// the value the server sends (nil for a JSON null) — server.go's handler for the method. requestID tags
// the request's context (core.WithRequestID), which the checker pool's request affinity keys on.
func (x *XtscLanguageService) XtscLSRequest(ctx context.Context, requestID string, method string, params json.Value) (any, error) {
	ctx = core.WithRequestID(lsproto.WithClientCapabilities(ctx, x.Caps), requestID)
	switch method {
	case "textDocument/hover":
		var p lsproto.HoverParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideHover(ctx, &p)
	case "textDocument/definition":
		var p lsproto.DefinitionParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideDefinition(ctx, p.TextDocument.Uri, p.Position)
	case "textDocument/typeDefinition":
		var p lsproto.TypeDefinitionParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideTypeDefinition(ctx, p.TextDocument.Uri, p.Position)
	case "textDocument/references":
		var p lsproto.ReferenceParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideReferences(ctx, &p, &xtscOrchestrator{x})
	case "textDocument/implementation":
		var p lsproto.ImplementationParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideImplementations(ctx, &p, &xtscOrchestrator{x})
	case "textDocument/completion":
		var p lsproto.CompletionParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		resp, err := x.languageService(p.TextDocumentURI()).ProvideCompletion(ctx, p.TextDocument.Uri, p.Position, p.Context)
		if errors.Is(err, ls.ErrNeedsAutoImports) {
			// The project system's auto-import registry is not ported: the client must switch
			// auto-import completions off (js/ts.suggest.autoImports: false).
			return nil, fmt.Errorf("completion needs auto-imports, which the port does not provide: %w", err)
		}
		return resp, err
	case "textDocument/signatureHelp":
		var p lsproto.SignatureHelpParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideSignatureHelp(ctx, p.TextDocument.Uri, p.Position, p.Context)
	case "textDocument/documentHighlight":
		var p lsproto.DocumentHighlightParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		return x.languageService(p.TextDocumentURI()).ProvideDocumentHighlights(ctx, p.TextDocument.Uri, p.Position)
	case "textDocument/diagnostic":
		var p lsproto.DocumentDiagnosticParams
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, err
		}
		ctx = core.WithCheckerLifetime(ctx, core.CheckerLifetimeDiagnostics)
		return x.languageService(p.TextDocumentURI()).ProvideDiagnostics(ctx, p.TextDocument.Uri)
	}
	return nil, fmt.Errorf("%w: %s", lsproto.ErrorCodeMethodNotFound, method)
}

// xtscOrchestrator is the server's crossProjectOrchestrator for a session of ONE configured project:
// every request's projects are that project, when it contains the file.
type xtscOrchestrator struct {
	x *XtscLanguageService
}

func (o *xtscOrchestrator) GetDefaultProject() ls.Project {
	return o.x.Project
}

func (o *xtscOrchestrator) GetAllProjectsForInitialRequest() []ls.Project {
	return []ls.Project{o.x.Project}
}

func (o *xtscOrchestrator) GetLanguageServiceForProjectWithFile(ctx context.Context, p ls.Project, uri lsproto.DocumentUri) *ls.LanguageService {
	return o.x.languageService(uri)
}

func (o *xtscOrchestrator) GetProjectsForFile(ctx context.Context, uri lsproto.DocumentUri) ([]ls.Project, error) {
	if o.x.Project.HasFile(uri.FileName()) {
		return []ls.Project{o.x.Project}, nil
	}
	return nil, nil
}

func (o *xtscOrchestrator) GetProjectsLoadingProjectTree(ctx context.Context, requestedProjectTrees *collections.Set[tspath.Path]) iter.Seq[ls.Project] {
	return func(yield func(ls.Project) bool) {
		yield(o.x.Project)
	}
}
