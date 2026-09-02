/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.SourceFileEntry
import kotlin.test.Test

/**
 * (EXT.11c) THE FOURTH FIXTURE-LADDER RUNG: the generator over the
 * `rxjs@7.8.2` declaration files that exercise the three mechanisms the
 * whole-package probe found and the core rung could not show — TWENTY-ONE
 * files under `dist/types/internal`, the thirteen that carry a shape plus
 * their transitive `import` closure within `dist/types` (index files
 * deliberately excluded; the closure is computed, not chosen) — gated by
 * the metadata compile.
 *
 * The fixtures below are the verbatim declaration files of the `rxjs` npm
 * package, version 7.8.2 — Apache License 2.0, Copyright (c) 2015-2018 Google,
 * Inc., Netflix, Inc., Microsoft Corp. and contributors
 * (https://github.com/reactivex/rxjs) — embedded here as test INPUT under the
 * licence's terms: this notice retains the work's copyright statement, the
 * licence identifier (`SPDX-License-Identifier: Apache-2.0`) and the pointer
 * to the licence text (https://www.apache.org/licenses/LICENSE-2.0), which is
 * what a redistribution of an unmodified Apache-licensed source excerpt owes.
 * A `$` in the sources is spelled `${'$'}` inside these raw strings.
 *
 * What each seed exercises, and what it compiled to before this rung:
 *
 *  - `ajax/errors.d.ts`, `util/ArgumentOutOfRangeError.d.ts`,
 *    `util/EmptyError.d.ts`, `operators/timeout.d.ts` — the companion-value
 *    idiom (`export interface AjaxError …` + `export declare const AjaxError:
 *    AjaxErrorCtor`): `Conflicting declarations`, five pairs here;
 *  - `operators/first.d.ts`, `operators/last.d.ts` — overloads differing
 *    only in nullability (`defaultValue: D` vs `D?`) or in type-parameter
 *    names (`<T, D>` vs `<T, S>`): `Conflicting overloads`;
 *  - `observable/of.d.ts` — a bare type parameter against `any` at equal
 *    arity (`<T> of(value: T)` vs `<A> of(...valuesAndScheduler)` once the
 *    rest type falls back);
 *  - `observable/combineLatest.d.ts`, `observable/forkJoin.d.ts`,
 *    `observable/pairs.d.ts` — `<A> f(sources: Any?)` against `<T> f(arg: T)`;
 *  - `observable/zip.d.ts` with `operators/zip.d.ts` — one name across TWO
 *    files whose two-parameter overloads differ only in type-parameter names;
 *  - `observable/ConnectableObservable.d.ts` — a subclass REDECLARING an
 *    inherited `var` narrower (`source: Observable<T>` over `Observable`'s
 *    `source: Observable<any> | undefined`): a `var` override type mismatch.
 *
 * The closure: `types`, `Observable`, `Subject`, `Subscription`,
 * `Subscriber`, `Operator`, `AnyCatcher` and `ajax/types`.
 */
class KotlinExternalsRxjsExtrasGateTest {

    private val typesDts = """
/// <reference lib="esnext.asynciterable" />
import { Observable } from './Observable';
import { Subscription } from './Subscription';
/**
 * Note: This will add Symbol.observable globally for all TypeScript users,
 * however, we are no longer polyfilling Symbol.observable
 */
declare global {
    interface SymbolConstructor {
        readonly observable: symbol;
    }
}
/**
 * A function type interface that describes a function that accepts one parameter `T`
 * and returns another parameter `R`.
 *
 * Usually used to describe {@link OperatorFunction} - it always takes a single
 * parameter (the source Observable) and returns another Observable.
 */
export interface UnaryFunction<T, R> {
    (source: T): R;
}
export interface OperatorFunction<T, R> extends UnaryFunction<Observable<T>, Observable<R>> {
}
export declare type FactoryOrValue<T> = T | (() => T);
/**
 * A function type interface that describes a function that accepts and returns a parameter of the same type.
 *
 * Used to describe {@link OperatorFunction} with the only one type: `OperatorFunction<T, T>`.
 *
 */
export interface MonoTypeOperatorFunction<T> extends OperatorFunction<T, T> {
}
/**
 * A value and the time at which it was emitted.
 *
 * Emitted by the `timestamp` operator
 *
 * @see {@link timestamp}
 */
export interface Timestamp<T> {
    value: T;
    /**
     * The timestamp. By default, this is in epoch milliseconds.
     * Could vary based on the timestamp provider passed to the operator.
     */
    timestamp: number;
}
/**
 * A value emitted and the amount of time since the last value was emitted.
 *
 * Emitted by the `timeInterval` operator.
 *
 * @see {@link timeInterval}
 */
export interface TimeInterval<T> {
    value: T;
    /**
     * The amount of time between this value's emission and the previous value's emission.
     * If this is the first emitted value, then it will be the amount of time since subscription
     * started.
     */
    interval: number;
}
export interface Unsubscribable {
    unsubscribe(): void;
}
export declare type TeardownLogic = Subscription | Unsubscribable | (() => void) | void;
export interface SubscriptionLike extends Unsubscribable {
    unsubscribe(): void;
    readonly closed: boolean;
}
/**
 * @deprecated Do not use. Most likely you want to use `ObservableInput`. Will be removed in v8.
 */
export declare type SubscribableOrPromise<T> = Subscribable<T> | Subscribable<never> | PromiseLike<T> | InteropObservable<T>;
/** OBSERVABLE INTERFACES */
export interface Subscribable<T> {
    subscribe(observer: Partial<Observer<T>>): Unsubscribable;
}
/**
 * Valid types that can be converted to observables.
 */
export declare type ObservableInput<T> = Observable<T> | InteropObservable<T> | AsyncIterable<T> | PromiseLike<T> | ArrayLike<T> | Iterable<T> | ReadableStreamLike<T>;
/**
 * @deprecated Renamed to {@link InteropObservable }. Will be removed in v8.
 */
export declare type ObservableLike<T> = InteropObservable<T>;
/**
 * An object that implements the `Symbol.observable` interface.
 */
export interface InteropObservable<T> {
    [Symbol.observable]: () => Subscribable<T>;
}
/**
 * A notification representing a "next" from an observable.
 * Can be used with {@link dematerialize}.
 */
export interface NextNotification<T> {
    /** The kind of notification. Always "N" */
    kind: 'N';
    /** The value of the notification. */
    value: T;
}
/**
 * A notification representing an "error" from an observable.
 * Can be used with {@link dematerialize}.
 */
export interface ErrorNotification {
    /** The kind of notification. Always "E" */
    kind: 'E';
    error: any;
}
/**
 * A notification representing a "completion" from an observable.
 * Can be used with {@link dematerialize}.
 */
export interface CompleteNotification {
    kind: 'C';
}
/**
 * Valid observable notification types.
 */
export declare type ObservableNotification<T> = NextNotification<T> | ErrorNotification | CompleteNotification;
export interface NextObserver<T> {
    closed?: boolean;
    next: (value: T) => void;
    error?: (err: any) => void;
    complete?: () => void;
}
export interface ErrorObserver<T> {
    closed?: boolean;
    next?: (value: T) => void;
    error: (err: any) => void;
    complete?: () => void;
}
export interface CompletionObserver<T> {
    closed?: boolean;
    next?: (value: T) => void;
    error?: (err: any) => void;
    complete: () => void;
}
export declare type PartialObserver<T> = NextObserver<T> | ErrorObserver<T> | CompletionObserver<T>;
/**
 * An object interface that defines a set of callback functions a user can use to get
 * notified of any set of {@link Observable}
 * {@link guide/glossary-and-semantics#notification notification} events.
 *
 * For more info, please refer to {@link guide/observer this guide}.
 */
export interface Observer<T> {
    /**
     * A callback function that gets called by the producer during the subscription when
     * the producer "has" the `value`. It won't be called if `error` or `complete` callback
     * functions have been called, nor after the consumer has unsubscribed.
     *
     * For more info, please refer to {@link guide/glossary-and-semantics#next this guide}.
     */
    next: (value: T) => void;
    /**
     * A callback function that gets called by the producer if and when it encountered a
     * problem of any kind. The errored value will be provided through the `err` parameter.
     * This callback can't be called more than one time, it can't be called if the
     * `complete` callback function have been called previously, nor it can't be called if
     * the consumer has unsubscribed.
     *
     * For more info, please refer to {@link guide/glossary-and-semantics#error this guide}.
     */
    error: (err: any) => void;
    /**
     * A callback function that gets called by the producer if and when it has no more
     * values to provide (by calling `next` callback function). This means that no error
     * has happened. This callback can't be called more than one time, it can't be called
     * if the `error` callback function have been called previously, nor it can't be called
     * if the consumer has unsubscribed.
     *
     * For more info, please refer to {@link guide/glossary-and-semantics#complete this guide}.
     */
    complete: () => void;
}
export interface SubjectLike<T> extends Observer<T>, Subscribable<T> {
}
export interface SchedulerLike extends TimestampProvider {
    schedule<T>(work: (this: SchedulerAction<T>, state: T) => void, delay: number, state: T): Subscription;
    schedule<T>(work: (this: SchedulerAction<T>, state?: T) => void, delay: number, state?: T): Subscription;
    schedule<T>(work: (this: SchedulerAction<T>, state?: T) => void, delay?: number, state?: T): Subscription;
}
export interface SchedulerAction<T> extends Subscription {
    schedule(state?: T, delay?: number): Subscription;
}
/**
 * This is a type that provides a method to allow RxJS to create a numeric timestamp
 */
export interface TimestampProvider {
    /**
     * Returns a timestamp as a number.
     *
     * This is used by types like `ReplaySubject` or operators like `timestamp` to calculate
     * the amount of time passed between events.
     */
    now(): number;
}
/**
 * Extracts the type from an `ObservableInput<any>`. If you have
 * `O extends ObservableInput<any>` and you pass in `Observable<number>`, or
 * `Promise<number>`, etc, it will type as `number`.
 */
export declare type ObservedValueOf<O> = O extends ObservableInput<infer T> ? T : never;
/**
 * Extracts a union of element types from an `ObservableInput<any>[]`.
 * If you have `O extends ObservableInput<any>[]` and you pass in
 * `Observable<string>[]` or `Promise<string>[]` you would get
 * back a type of `string`.
 * If you pass in `[Observable<string>, Observable<number>]` you would
 * get back a type of `string | number`.
 */
export declare type ObservedValueUnionFromArray<X> = X extends Array<ObservableInput<infer T>> ? T : never;
/**
 * @deprecated Renamed to {@link ObservedValueUnionFromArray}. Will be removed in v8.
 */
export declare type ObservedValuesFromArray<X> = ObservedValueUnionFromArray<X>;
/**
 * Extracts a tuple of element types from an `ObservableInput<any>[]`.
 * If you have `O extends ObservableInput<any>[]` and you pass in
 * `[Observable<string>, Observable<number>]` you would get back a type
 * of `[string, number]`.
 */
export declare type ObservedValueTupleFromArray<X> = {
    [K in keyof X]: ObservedValueOf<X[K]>;
};
/**
 * Used to infer types from arguments to functions like {@link forkJoin}.
 * So that you can have `forkJoin([Observable<A>, PromiseLike<B>]): Observable<[A, B]>`
 * et al.
 */
export declare type ObservableInputTuple<T> = {
    [K in keyof T]: ObservableInput<T[K]>;
};
/**
 * Constructs a new tuple with the specified type at the head.
 * If you declare `Cons<A, [B, C]>` you will get back `[A, B, C]`.
 */
export declare type Cons<X, Y extends readonly any[]> = ((arg: X, ...rest: Y) => any) extends (...args: infer U) => any ? U : never;
/**
 * Extracts the head of a tuple.
 * If you declare `Head<[A, B, C]>` you will get back `A`.
 */
export declare type Head<X extends readonly any[]> = ((...args: X) => any) extends (arg: infer U, ...rest: any[]) => any ? U : never;
/**
 * Extracts the tail of a tuple.
 * If you declare `Tail<[A, B, C]>` you will get back `[B, C]`.
 */
export declare type Tail<X extends readonly any[]> = ((...args: X) => any) extends (arg: any, ...rest: infer U) => any ? U : never;
/**
 * Extracts the generic value from an Array type.
 * If you have `T extends Array<any>`, and pass a `string[]` to it,
 * `ValueFromArray<T>` will return the actual type of `string`.
 */
export declare type ValueFromArray<A extends readonly unknown[]> = A extends Array<infer T> ? T : never;
/**
 * Gets the value type from an {@link ObservableNotification}, if possible.
 */
export declare type ValueFromNotification<T> = T extends {
    kind: 'N' | 'E' | 'C';
} ? T extends NextNotification<any> ? T extends {
    value: infer V;
} ? V : undefined : never : never;
/**
 * A simple type to represent a gamut of "falsy" values... with a notable exception:
 * `NaN` is "falsy" however, it is not and cannot be typed via TypeScript. See
 * comments here: https://github.com/microsoft/TypeScript/issues/28682#issuecomment-707142417
 */
export declare type Falsy = null | undefined | false | 0 | -0 | 0n | '';
export declare type TruthyTypesOf<T> = T extends Falsy ? never : T;
interface ReadableStreamDefaultReaderLike<T> {
    read(): PromiseLike<{
        done: false;
        value: T;
    } | {
        done: true;
        value?: undefined;
    }>;
    releaseLock(): void;
}
/**
 * The base signature RxJS will look for to identify and use
 * a [ReadableStream](https://streams.spec.whatwg.org/#rs-class)
 * as an {@link ObservableInput} source.
 */
export interface ReadableStreamLike<T> {
    getReader(): ReadableStreamDefaultReaderLike<T>;
}
/**
 * An observable with a `connect` method that is used to create a subscription
 * to an underlying source, connecting it with all consumers via a multicast.
 */
export interface Connectable<T> extends Observable<T> {
    /**
     * (Idempotent) Calling this method will connect the underlying source observable to all subscribed consumers
     * through an underlying {@link Subject}.
     * @returns A subscription, that when unsubscribed, will "disconnect" the source from the connector subject,
     * severing notifications to all consumers.
     */
    connect(): Subscription;
}
export {};
//# sourceMappingURL=types.d.ts.map"""

    private val observableDts = """
import { Operator } from './Operator';
import { Subscriber } from './Subscriber';
import { Subscription } from './Subscription';
import { TeardownLogic, OperatorFunction, Subscribable, Observer } from './types';
/**
 * A representation of any set of values over any amount of time. This is the most basic building block
 * of RxJS.
 */
export declare class Observable<T> implements Subscribable<T> {
    /**
     * @deprecated Internal implementation detail, do not use directly. Will be made internal in v8.
     */
    source: Observable<any> | undefined;
    /**
     * @deprecated Internal implementation detail, do not use directly. Will be made internal in v8.
     */
    operator: Operator<any, T> | undefined;
    /**
     * @param subscribe The function that is called when the Observable is
     * initially subscribed to. This function is given a Subscriber, to which new values
     * can be `next`ed, or an `error` method can be called to raise an error, or
     * `complete` can be called to notify of a successful completion.
     */
    constructor(subscribe?: (this: Observable<T>, subscriber: Subscriber<T>) => TeardownLogic);
    /**
     * Creates a new Observable by calling the Observable constructor
     * @param subscribe the subscriber function to be passed to the Observable constructor
     * @return A new observable.
     * @deprecated Use `new Observable()` instead. Will be removed in v8.
     */
    static create: (...args: any[]) => any;
    /**
     * Creates a new Observable, with this Observable instance as the source, and the passed
     * operator defined as the new observable's operator.
     * @param operator the operator defining the operation to take on the observable
     * @return A new observable with the Operator applied.
     * @deprecated Internal implementation detail, do not use directly. Will be made internal in v8.
     * If you have implemented an operator using `lift`, it is recommended that you create an
     * operator by simply returning `new Observable()` directly. See "Creating new operators from
     * scratch" section here: https://rxjs.dev/guide/operators
     */
    lift<R>(operator?: Operator<T, R>): Observable<R>;
    subscribe(observerOrNext?: Partial<Observer<T>> | ((value: T) => void)): Subscription;
    /** @deprecated Instead of passing separate callback arguments, use an observer argument. Signatures taking separate callback arguments will be removed in v8. Details: https://rxjs.dev/deprecations/subscribe-arguments */
    subscribe(next?: ((value: T) => void) | null, error?: ((error: any) => void) | null, complete?: (() => void) | null): Subscription;
    /**
     * Used as a NON-CANCELLABLE means of subscribing to an observable, for use with
     * APIs that expect promises, like `async/await`. You cannot unsubscribe from this.
     *
     * **WARNING**: Only use this with observables you *know* will complete. If the source
     * observable does not complete, you will end up with a promise that is hung up, and
     * potentially all of the state of an async function hanging out in memory. To avoid
     * this situation, look into adding something like {@link timeout}, {@link take},
     * {@link takeWhile}, or {@link takeUntil} amongst others.
     *
     * #### Example
     *
     * ```ts
     * import { interval, take } from 'rxjs';
     *
     * const source${'$'} = interval(1000).pipe(take(4));
     *
     * async function getTotal() {
     *   let total = 0;
     *
     *   await source${'$'}.forEach(value => {
     *     total += value;
     *     console.log('observable -> ' + value);
     *   });
     *
     *   return total;
     * }
     *
     * getTotal().then(
     *   total => console.log('Total: ' + total)
     * );
     *
     * // Expected:
     * // 'observable -> 0'
     * // 'observable -> 1'
     * // 'observable -> 2'
     * // 'observable -> 3'
     * // 'Total: 6'
     * ```
     *
     * @param next A handler for each value emitted by the observable.
     * @return A promise that either resolves on observable completion or
     * rejects with the handled error.
     */
    forEach(next: (value: T) => void): Promise<void>;
    /**
     * @param next a handler for each value emitted by the observable
     * @param promiseCtor a constructor function used to instantiate the Promise
     * @return a promise that either resolves on observable completion or
     *  rejects with the handled error
     * @deprecated Passing a Promise constructor will no longer be available
     * in upcoming versions of RxJS. This is because it adds weight to the library, for very
     * little benefit. If you need this functionality, it is recommended that you either
     * polyfill Promise, or you create an adapter to convert the returned native promise
     * to whatever promise implementation you wanted. Will be removed in v8.
     */
    forEach(next: (value: T) => void, promiseCtor: PromiseConstructorLike): Promise<void>;
    pipe(): Observable<T>;
    pipe<A>(op1: OperatorFunction<T, A>): Observable<A>;
    pipe<A, B>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>): Observable<B>;
    pipe<A, B, C>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>): Observable<C>;
    pipe<A, B, C, D>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>): Observable<D>;
    pipe<A, B, C, D, E>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>, op5: OperatorFunction<D, E>): Observable<E>;
    pipe<A, B, C, D, E, F>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>, op5: OperatorFunction<D, E>, op6: OperatorFunction<E, F>): Observable<F>;
    pipe<A, B, C, D, E, F, G>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>, op5: OperatorFunction<D, E>, op6: OperatorFunction<E, F>, op7: OperatorFunction<F, G>): Observable<G>;
    pipe<A, B, C, D, E, F, G, H>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>, op5: OperatorFunction<D, E>, op6: OperatorFunction<E, F>, op7: OperatorFunction<F, G>, op8: OperatorFunction<G, H>): Observable<H>;
    pipe<A, B, C, D, E, F, G, H, I>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>, op5: OperatorFunction<D, E>, op6: OperatorFunction<E, F>, op7: OperatorFunction<F, G>, op8: OperatorFunction<G, H>, op9: OperatorFunction<H, I>): Observable<I>;
    pipe<A, B, C, D, E, F, G, H, I>(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>, op3: OperatorFunction<B, C>, op4: OperatorFunction<C, D>, op5: OperatorFunction<D, E>, op6: OperatorFunction<E, F>, op7: OperatorFunction<F, G>, op8: OperatorFunction<G, H>, op9: OperatorFunction<H, I>, ...operations: OperatorFunction<any, any>[]): Observable<unknown>;
    /** @deprecated Replaced with {@link firstValueFrom} and {@link lastValueFrom}. Will be removed in v8. Details: https://rxjs.dev/deprecations/to-promise */
    toPromise(): Promise<T | undefined>;
    /** @deprecated Replaced with {@link firstValueFrom} and {@link lastValueFrom}. Will be removed in v8. Details: https://rxjs.dev/deprecations/to-promise */
    toPromise(PromiseCtor: typeof Promise): Promise<T | undefined>;
    /** @deprecated Replaced with {@link firstValueFrom} and {@link lastValueFrom}. Will be removed in v8. Details: https://rxjs.dev/deprecations/to-promise */
    toPromise(PromiseCtor: PromiseConstructorLike): Promise<T | undefined>;
}
//# sourceMappingURL=Observable.d.ts.map"""

    private val subjectDts = """
import { Operator } from './Operator';
import { Observable } from './Observable';
import { Observer, SubscriptionLike } from './types';
/**
 * A Subject is a special type of Observable that allows values to be
 * multicasted to many Observers. Subjects are like EventEmitters.
 *
 * Every Subject is an Observable and an Observer. You can subscribe to a
 * Subject, and you can call next to feed values as well as error and complete.
 */
export declare class Subject<T> extends Observable<T> implements SubscriptionLike {
    closed: boolean;
    private currentObservers;
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    observers: Observer<T>[];
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    isStopped: boolean;
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    hasError: boolean;
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    thrownError: any;
    /**
     * Creates a "subject" by basically gluing an observer to an observable.
     *
     * @deprecated Recommended you do not use. Will be removed at some point in the future. Plans for replacement still under discussion.
     */
    static create: (...args: any[]) => any;
    constructor();
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    lift<R>(operator: Operator<T, R>): Observable<R>;
    next(value: T): void;
    error(err: any): void;
    complete(): void;
    unsubscribe(): void;
    get observed(): boolean;
    /**
     * Creates a new Observable with this Subject as the source. You can do this
     * to create custom Observer-side logic of the Subject and conceal it from
     * code that uses the Observable.
     * @return Observable that this Subject casts to.
     */
    asObservable(): Observable<T>;
}
export declare class AnonymousSubject<T> extends Subject<T> {
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    destination?: Observer<T> | undefined;
    constructor(
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    destination?: Observer<T> | undefined, source?: Observable<T>);
    next(value: T): void;
    error(err: any): void;
    complete(): void;
}
//# sourceMappingURL=Subject.d.ts.map"""

    private val subscriptionDts = """
import { SubscriptionLike, TeardownLogic } from './types';
/**
 * Represents a disposable resource, such as the execution of an Observable. A
 * Subscription has one important method, `unsubscribe`, that takes no argument
 * and just disposes the resource held by the subscription.
 *
 * Additionally, subscriptions may be grouped together through the `add()`
 * method, which will attach a child Subscription to the current Subscription.
 * When a Subscription is unsubscribed, all its children (and its grandchildren)
 * will be unsubscribed as well.
 */
export declare class Subscription implements SubscriptionLike {
    private initialTeardown?;
    static EMPTY: Subscription;
    /**
     * A flag to indicate whether this Subscription has already been unsubscribed.
     */
    closed: boolean;
    private _parentage;
    /**
     * The list of registered finalizers to execute upon unsubscription. Adding and removing from this
     * list occurs in the {@link #add} and {@link #remove} methods.
     */
    private _finalizers;
    /**
     * @param initialTeardown A function executed first as part of the finalization
     * process that is kicked off when {@link #unsubscribe} is called.
     */
    constructor(initialTeardown?: (() => void) | undefined);
    /**
     * Disposes the resources held by the subscription. May, for instance, cancel
     * an ongoing Observable execution or cancel any other type of work that
     * started when the Subscription was created.
     */
    unsubscribe(): void;
    /**
     * Adds a finalizer to this subscription, so that finalization will be unsubscribed/called
     * when this subscription is unsubscribed. If this subscription is already {@link #closed},
     * because it has already been unsubscribed, then whatever finalizer is passed to it
     * will automatically be executed (unless the finalizer itself is also a closed subscription).
     *
     * Closed Subscriptions cannot be added as finalizers to any subscription. Adding a closed
     * subscription to a any subscription will result in no operation. (A noop).
     *
     * Adding a subscription to itself, or adding `null` or `undefined` will not perform any
     * operation at all. (A noop).
     *
     * `Subscription` instances that are added to this instance will automatically remove themselves
     * if they are unsubscribed. Functions and {@link Unsubscribable} objects that you wish to remove
     * will need to be removed manually with {@link #remove}
     *
     * @param teardown The finalization logic to add to this subscription.
     */
    add(teardown: TeardownLogic): void;
    /**
     * Checks to see if a this subscription already has a particular parent.
     * This will signal that this subscription has already been added to the parent in question.
     * @param parent the parent to check for
     */
    private _hasParent;
    /**
     * Adds a parent to this subscription so it can be removed from the parent if it
     * unsubscribes on it's own.
     *
     * NOTE: THIS ASSUMES THAT {@link _hasParent} HAS ALREADY BEEN CHECKED.
     * @param parent The parent subscription to add
     */
    private _addParent;
    /**
     * Called on a child when it is removed via {@link #remove}.
     * @param parent The parent to remove
     */
    private _removeParent;
    /**
     * Removes a finalizer from this subscription that was previously added with the {@link #add} method.
     *
     * Note that `Subscription` instances, when unsubscribed, will automatically remove themselves
     * from every other `Subscription` they have been added to. This means that using the `remove` method
     * is not a common thing and should be used thoughtfully.
     *
     * If you add the same finalizer instance of a function or an unsubscribable object to a `Subscription` instance
     * more than once, you will need to call `remove` the same number of times to remove all instances.
     *
     * All finalizer instances are removed to free up memory upon unsubscription.
     *
     * @param teardown The finalizer to remove from this subscription
     */
    remove(teardown: Exclude<TeardownLogic, void>): void;
}
export declare const EMPTY_SUBSCRIPTION: Subscription;
export declare function isSubscription(value: any): value is Subscription;
//# sourceMappingURL=Subscription.d.ts.map"""

    private val subscriberDts = """
import { Observer } from './types';
import { Subscription } from './Subscription';
/**
 * Implements the {@link Observer} interface and extends the
 * {@link Subscription} class. While the {@link Observer} is the public API for
 * consuming the values of an {@link Observable}, all Observers get converted to
 * a Subscriber, in order to provide Subscription-like capabilities such as
 * `unsubscribe`. Subscriber is a common type in RxJS, and crucial for
 * implementing operators, but it is rarely used as a public API.
 */
export declare class Subscriber<T> extends Subscription implements Observer<T> {
    /**
     * A static factory for a Subscriber, given a (potentially partial) definition
     * of an Observer.
     * @param next The `next` callback of an Observer.
     * @param error The `error` callback of an
     * Observer.
     * @param complete The `complete` callback of an
     * Observer.
     * @return A Subscriber wrapping the (partially defined)
     * Observer represented by the given arguments.
     * @deprecated Do not use. Will be removed in v8. There is no replacement for this
     * method, and there is no reason to be creating instances of `Subscriber` directly.
     * If you have a specific use case, please file an issue.
     */
    static create<T>(next?: (x?: T) => void, error?: (e?: any) => void, complete?: () => void): Subscriber<T>;
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    protected isStopped: boolean;
    /** @deprecated Internal implementation detail, do not use directly. Will be made internal in v8. */
    protected destination: Subscriber<any> | Observer<any>;
    /**
     * @deprecated Internal implementation detail, do not use directly. Will be made internal in v8.
     * There is no reason to directly create an instance of Subscriber. This type is exported for typings reasons.
     */
    constructor(destination?: Subscriber<any> | Observer<any>);
    /**
     * The {@link Observer} callback to receive notifications of type `next` from
     * the Observable, with a value. The Observable may call this method 0 or more
     * times.
     * @param value The `next` value.
     */
    next(value: T): void;
    /**
     * The {@link Observer} callback to receive notifications of type `error` from
     * the Observable, with an attached `Error`. Notifies the Observer that
     * the Observable has experienced an error condition.
     * @param err The `error` exception.
     */
    error(err?: any): void;
    /**
     * The {@link Observer} callback to receive a valueless notification of type
     * `complete` from the Observable. Notifies the Observer that the Observable
     * has finished sending push-based notifications.
     */
    complete(): void;
    unsubscribe(): void;
    protected _next(value: T): void;
    protected _error(err: any): void;
    protected _complete(): void;
}
export declare class SafeSubscriber<T> extends Subscriber<T> {
    constructor(observerOrNext?: Partial<Observer<T>> | ((value: T) => void) | null, error?: ((e?: any) => void) | null, complete?: (() => void) | null);
}
/**
 * The observer used as a stub for subscriptions where the user did not
 * pass any arguments to `subscribe`. Comes with the default error handling
 * behavior.
 */
export declare const EMPTY_OBSERVER: Readonly<Observer<any>> & {
    closed: true;
};
//# sourceMappingURL=Subscriber.d.ts.map"""

    private val operatorDts = """
import { Subscriber } from './Subscriber';
import { TeardownLogic } from './types';
/***
 * @deprecated Internal implementation detail, do not use directly. Will be made internal in v8.
 */
export interface Operator<T, R> {
    call(subscriber: Subscriber<R>, source: any): TeardownLogic;
}
//# sourceMappingURL=Operator.d.ts.map"""

    private val anyCatcherDts = """
declare const anyCatcherSymbol: unique symbol;
/**
 * This is just a type that we're using to identify `any` being passed to
 * function overloads. This is used because of situations like {@link forkJoin},
 * where it could return an `Observable<T[]>` or an `Observable<{ [key: K]: T }>`,
 * so `forkJoin(any)` would mean we need to return `Observable<unknown>`.
 */
export declare type AnyCatcher = typeof anyCatcherSymbol;
export {};
//# sourceMappingURL=AnyCatcher.d.ts.map"""

    private val ajaxTypesDts = """
import { PartialObserver } from '../types';
/**
 * Valid Ajax direction types. Prefixes the event `type` in the
 * {@link AjaxResponse} object with "upload_" for events related
 * to uploading and "download_" for events related to downloading.
 */
export declare type AjaxDirection = 'upload' | 'download';
export declare type ProgressEventType = 'loadstart' | 'progress' | 'load';
export declare type AjaxResponseType = `${'$'}{AjaxDirection}_${'$'}{ProgressEventType}`;
/**
 * The object containing values RxJS used to make the HTTP request.
 *
 * This is provided in {@link AjaxError} instances as the `request`
 * object.
 */
export interface AjaxRequest {
    /**
     * The URL requested.
     */
    url: string;
    /**
     * The body to send over the HTTP request.
     */
    body?: any;
    /**
     * The HTTP method used to make the HTTP request.
     */
    method: string;
    /**
     * Whether or not the request was made asynchronously.
     */
    async: boolean;
    /**
     * The headers sent over the HTTP request.
     */
    headers: Readonly<Record<string, any>>;
    /**
     * The timeout value used for the HTTP request.
     * Note: this is only honored if the request is asynchronous (`async` is `true`).
     */
    timeout: number;
    /**
     * The user credentials user name sent with the HTTP request.
     */
    user?: string;
    /**
     * The user credentials password sent with the HTTP request.
     */
    password?: string;
    /**
     * Whether or not the request was a CORS request.
     */
    crossDomain: boolean;
    /**
     * Whether or not a CORS request was sent with credentials.
     * If `false`, will also ignore cookies in the CORS response.
     */
    withCredentials: boolean;
    /**
     * The [`responseType`](https://developer.mozilla.org/en-US/docs/Web/API/XMLHttpRequest/responseType) set before sending the request.
     */
    responseType: XMLHttpRequestResponseType;
}
/**
 * Configuration for the {@link ajax} creation function.
 */
export interface AjaxConfig {
    /** The address of the resource to request via HTTP. */
    url: string;
    /**
     * The body of the HTTP request to send.
     *
     * This is serialized, by default, based off of the value of the `"content-type"` header.
     * For example, if the `"content-type"` is `"application/json"`, the body will be serialized
     * as JSON. If the `"content-type"` is `"application/x-www-form-urlencoded"`, whatever object passed
     * to the body will be serialized as URL, using key-value pairs based off of the keys and values of the object.
     * In all other cases, the body will be passed directly.
     */
    body?: any;
    /**
     * Whether or not to send the request asynchronously. Defaults to `true`.
     * If set to `false`, this will block the thread until the AJAX request responds.
     */
    async?: boolean;
    /**
     * The HTTP Method to use for the request. Defaults to "GET".
     */
    method?: string;
    /**
     * The HTTP headers to apply.
     *
     * Note that, by default, RxJS will add the following headers under certain conditions:
     *
     * 1. If the `"content-type"` header is **NOT** set, and the `body` is [`FormData`](https://developer.mozilla.org/en-US/docs/Web/API/FormData),
     *    a `"content-type"` of `"application/x-www-form-urlencoded; charset=UTF-8"` will be set automatically.
     * 2. If the `"x-requested-with"` header is **NOT** set, and the `crossDomain` configuration property is **NOT** explicitly set to `true`,
     *    (meaning it is not a CORS request), a `"x-requested-with"` header with a value of `"XMLHttpRequest"` will be set automatically.
     *    This header is generally meaningless, and is set by libraries and frameworks using `XMLHttpRequest` to make HTTP requests.
     */
    headers?: Readonly<Record<string, any>>;
    /**
     * The time to wait before causing the underlying XMLHttpRequest to timeout. This is only honored if the
     * `async` configuration setting is unset or set to `true`. Defaults to `0`, which is idiomatic for "never timeout".
     */
    timeout?: number;
    /** The user credentials user name to send with the HTTP request */
    user?: string;
    /** The user credentials password to send with the HTTP request*/
    password?: string;
    /**
     * Whether or not to send the HTTP request as a CORS request.
     * Defaults to `false`.
     *
     * @deprecated Will be removed in version 8. Cross domain requests and what creates a cross
     * domain request, are dictated by the browser, and a boolean that forces it to be cross domain
     * does not make sense. If you need to force cross domain, make sure you're making a secure request,
     * then add a custom header to the request or use `withCredentials`. For more information on what
     * triggers a cross domain request, see the [MDN documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Access_control_CORS#Requests_with_credentials).
     * In particular, the section on [Simple Requests](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS#Simple_requests) is useful
     * for understanding when CORS will not be used.
     */
    crossDomain?: boolean;
    /**
     * To send user credentials in a CORS request, set to `true`. To exclude user credentials from
     * a CORS request, _OR_ when cookies are to be ignored by the CORS response, set to `false`.
     *
     * Defaults to `false`.
     */
    withCredentials?: boolean;
    /**
     * The name of your site's XSRF cookie.
     */
    xsrfCookieName?: string;
    /**
     * The name of a custom header that you can use to send your XSRF cookie.
     */
    xsrfHeaderName?: string;
    /**
     * Can be set to change the response type.
     * Valid values are `"arraybuffer"`, `"blob"`, `"document"`, `"json"`, and `"text"`.
     * Note that the type of `"document"` (such as an XML document) is ignored if the global context is
     * not `Window`.
     *
     * Defaults to `"json"`.
     */
    responseType?: XMLHttpRequestResponseType;
    /**
     * An optional factory used to create the XMLHttpRequest object used to make the AJAX request.
     * This is useful in environments that lack `XMLHttpRequest`, or in situations where you
     * wish to override the default `XMLHttpRequest` for some reason.
     *
     * If not provided, the `XMLHttpRequest` in global scope will be used.
     *
     * NOTE: This AJAX implementation relies on the built-in serialization and setting
     * of Content-Type headers that is provided by standards-compliant XMLHttpRequest implementations,
     * be sure any implementation you use meets that standard.
     */
    createXHR?: () => XMLHttpRequest;
    /**
     * An observer for watching the upload progress of an HTTP request. Will
     * emit progress events, and completes on the final upload load event, will error for
     * any XHR error or timeout.
     *
     * This will **not** error for errored status codes. Rather, it will always _complete_ when
     * the HTTP response comes back.
     *
     * @deprecated If you're looking for progress events, use {@link includeDownloadProgress} and
     * {@link includeUploadProgress} instead. Will be removed in v8.
     */
    progressSubscriber?: PartialObserver<ProgressEvent>;
    /**
     * If `true`, will emit all download progress and load complete events as {@link AjaxResponse}
     * from the observable. The final download event will also be emitted as a {@link AjaxResponse}.
     *
     * If both this and {@link includeUploadProgress} are `false`, then only the {@link AjaxResponse} will
     * be emitted from the resulting observable.
     */
    includeDownloadProgress?: boolean;
    /**
     * If `true`, will emit all upload progress and load complete events as {@link AjaxResponse}
     * from the observable. The final download event will also be emitted as a {@link AjaxResponse}.
     *
     * If both this and {@link includeDownloadProgress} are `false`, then only the {@link AjaxResponse} will
     * be emitted from the resulting observable.
     */
    includeUploadProgress?: boolean;
    /**
     * Query string parameters to add to the URL in the request.
     * <em>This will require a polyfill for `URL` and `URLSearchParams` in Internet Explorer!</em>
     *
     * Accepts either a query string, a `URLSearchParams` object, a dictionary of key/value pairs, or an
     * array of key/value entry tuples. (Essentially, it takes anything that `new URLSearchParams` would normally take).
     *
     * If, for some reason you have a query string in the `url` argument, this will append to the query string in the url,
     * but it will also overwrite the value of any keys that are an exact match. In other words, a url of `/test?a=1&b=2`,
     * with queryParams of `{ b: 5, c: 6 }` will result in a url of roughly `/test?a=1&b=5&c=6`.
     */
    queryParams?: string | URLSearchParams | Record<string, string | number | boolean | string[] | number[] | boolean[]> | [string, string | number | boolean | string[] | number[] | boolean[]][];
}
//# sourceMappingURL=types.d.ts.map"""

    private val ajaxErrorsDts = """
import { AjaxRequest } from './types';
/**
 * A normalized AJAX error.
 *
 * @see {@link ajax}
 */
export interface AjaxError extends Error {
    /**
     * The XHR instance associated with the error.
     */
    xhr: XMLHttpRequest;
    /**
     * The AjaxRequest associated with the error.
     */
    request: AjaxRequest;
    /**
     * The HTTP status code, if the request has completed. If not,
     * it is set to `0`.
     */
    status: number;
    /**
     * The responseType (e.g. 'json', 'arraybuffer', or 'xml').
     */
    responseType: XMLHttpRequestResponseType;
    /**
     * The response data.
     */
    response: any;
}
export interface AjaxErrorCtor {
    /**
     * @deprecated Internal implementation detail. Do not construct error instances.
     * Cannot be tagged as internal: https://github.com/ReactiveX/rxjs/issues/6269
     */
    new (message: string, xhr: XMLHttpRequest, request: AjaxRequest): AjaxError;
}
/**
 * Thrown when an error occurs during an AJAX request.
 * This is only exported because it is useful for checking to see if an error
 * is an `instanceof AjaxError`. DO NOT create new instances of `AjaxError` with
 * the constructor.
 *
 * @see {@link ajax}
 */
export declare const AjaxError: AjaxErrorCtor;
export interface AjaxTimeoutError extends AjaxError {
}
export interface AjaxTimeoutErrorCtor {
    /**
     * @deprecated Internal implementation detail. Do not construct error instances.
     * Cannot be tagged as internal: https://github.com/ReactiveX/rxjs/issues/6269
     */
    new (xhr: XMLHttpRequest, request: AjaxRequest): AjaxTimeoutError;
}
/**
 * Thrown when an AJAX request times out. Not to be confused with {@link TimeoutError}.
 *
 * This is exported only because it is useful for checking to see if errors are an
 * `instanceof AjaxTimeoutError`. DO NOT use the constructor to create an instance of
 * this type.
 *
 * @see {@link ajax}
 */
export declare const AjaxTimeoutError: AjaxTimeoutErrorCtor;
//# sourceMappingURL=errors.d.ts.map"""

    private val observableConnectableObservableDts = """
import { Subject } from '../Subject';
import { Observable } from '../Observable';
import { Subscription } from '../Subscription';
/**
 * @class ConnectableObservable<T>
 * @deprecated Will be removed in v8. Use {@link connectable} to create a connectable observable.
 * If you are using the `refCount` method of `ConnectableObservable`, use the {@link share} operator
 * instead.
 * Details: https://rxjs.dev/deprecations/multicasting
 */
export declare class ConnectableObservable<T> extends Observable<T> {
    source: Observable<T>;
    protected subjectFactory: () => Subject<T>;
    protected _subject: Subject<T> | null;
    protected _refCount: number;
    protected _connection: Subscription | null;
    /**
     * @param source The source observable
     * @param subjectFactory The factory that creates the subject used internally.
     * @deprecated Will be removed in v8. Use {@link connectable} to create a connectable observable.
     * `new ConnectableObservable(source, factory)` is equivalent to
     * `connectable(source, { connector: factory })`.
     * When the `refCount()` method is needed, the {@link share} operator should be used instead:
     * `new ConnectableObservable(source, factory).refCount()` is equivalent to
     * `source.pipe(share({ connector: factory }))`.
     * Details: https://rxjs.dev/deprecations/multicasting
     */
    constructor(source: Observable<T>, subjectFactory: () => Subject<T>);
    protected getSubject(): Subject<T>;
    protected _teardown(): void;
    /**
     * @deprecated {@link ConnectableObservable} will be removed in v8. Use {@link connectable} instead.
     * Details: https://rxjs.dev/deprecations/multicasting
     */
    connect(): Subscription;
    /**
     * @deprecated {@link ConnectableObservable} will be removed in v8. Use the {@link share} operator instead.
     * Details: https://rxjs.dev/deprecations/multicasting
     */
    refCount(): Observable<T>;
}
//# sourceMappingURL=ConnectableObservable.d.ts.map"""

    private val observableZipDts = """
import { Observable } from '../Observable';
import { ObservableInputTuple } from '../types';
export declare function zip<A extends readonly unknown[]>(sources: [...ObservableInputTuple<A>]): Observable<A>;
export declare function zip<A extends readonly unknown[], R>(sources: [...ObservableInputTuple<A>], resultSelector: (...values: A) => R): Observable<R>;
export declare function zip<A extends readonly unknown[]>(...sources: [...ObservableInputTuple<A>]): Observable<A>;
export declare function zip<A extends readonly unknown[], R>(...sourcesAndResultSelector: [...ObservableInputTuple<A>, (...values: A) => R]): Observable<R>;
//# sourceMappingURL=zip.d.ts.map"""

    private val operatorsZipDts = """
import { ObservableInputTuple, OperatorFunction, Cons } from '../types';
/** @deprecated Replaced with {@link zipWith}. Will be removed in v8. */
export declare function zip<T, A extends readonly unknown[]>(otherInputs: [...ObservableInputTuple<A>]): OperatorFunction<T, Cons<T, A>>;
/** @deprecated Replaced with {@link zipWith}. Will be removed in v8. */
export declare function zip<T, A extends readonly unknown[], R>(otherInputsAndProject: [...ObservableInputTuple<A>], project: (...values: Cons<T, A>) => R): OperatorFunction<T, R>;
/** @deprecated Replaced with {@link zipWith}. Will be removed in v8. */
export declare function zip<T, A extends readonly unknown[]>(...otherInputs: [...ObservableInputTuple<A>]): OperatorFunction<T, Cons<T, A>>;
/** @deprecated Replaced with {@link zipWith}. Will be removed in v8. */
export declare function zip<T, A extends readonly unknown[], R>(...otherInputsAndProject: [...ObservableInputTuple<A>, (...values: Cons<T, A>) => R]): OperatorFunction<T, R>;
//# sourceMappingURL=zip.d.ts.map"""

    private val operatorsFirstDts = """
import { Observable } from '../Observable';
import { OperatorFunction, TruthyTypesOf } from '../types';
export declare function first<T, D = T>(predicate?: null, defaultValue?: D): OperatorFunction<T, T | D>;
export declare function first<T>(predicate: BooleanConstructor): OperatorFunction<T, TruthyTypesOf<T>>;
export declare function first<T, D>(predicate: BooleanConstructor, defaultValue: D): OperatorFunction<T, TruthyTypesOf<T> | D>;
export declare function first<T, S extends T>(predicate: (value: T, index: number, source: Observable<T>) => value is S, defaultValue?: S): OperatorFunction<T, S>;
export declare function first<T, S extends T, D>(predicate: (value: T, index: number, source: Observable<T>) => value is S, defaultValue: D): OperatorFunction<T, S | D>;
export declare function first<T, D = T>(predicate: (value: T, index: number, source: Observable<T>) => boolean, defaultValue?: D): OperatorFunction<T, T | D>;
//# sourceMappingURL=first.d.ts.map"""

    private val operatorsLastDts = """
import { Observable } from '../Observable';
import { OperatorFunction, TruthyTypesOf } from '../types';
export declare function last<T>(predicate: BooleanConstructor): OperatorFunction<T, TruthyTypesOf<T>>;
export declare function last<T, D>(predicate: BooleanConstructor, defaultValue: D): OperatorFunction<T, TruthyTypesOf<T> | D>;
export declare function last<T, D = T>(predicate?: null, defaultValue?: D): OperatorFunction<T, T | D>;
export declare function last<T, S extends T>(predicate: (value: T, index: number, source: Observable<T>) => value is S, defaultValue?: S): OperatorFunction<T, S>;
export declare function last<T, D = T>(predicate: (value: T, index: number, source: Observable<T>) => boolean, defaultValue?: D): OperatorFunction<T, T | D>;
//# sourceMappingURL=last.d.ts.map"""

    private val observableOfDts = """
import { SchedulerLike, ValueFromArray } from '../types';
import { Observable } from '../Observable';
export declare function of(value: null): Observable<null>;
export declare function of(value: undefined): Observable<undefined>;
/** @deprecated The `scheduler` parameter will be removed in v8. Use `scheduled`. Details: https://rxjs.dev/deprecations/scheduler-argument */
export declare function of(scheduler: SchedulerLike): Observable<never>;
/** @deprecated The `scheduler` parameter will be removed in v8. Use `scheduled`. Details: https://rxjs.dev/deprecations/scheduler-argument */
export declare function of<A extends readonly unknown[]>(...valuesAndScheduler: [...A, SchedulerLike]): Observable<ValueFromArray<A>>;
export declare function of(): Observable<never>;
/** @deprecated Do not specify explicit type parameters. Signatures with type parameters that cannot be inferred will be removed in v8. */
export declare function of<T>(): Observable<T>;
export declare function of<T>(value: T): Observable<T>;
export declare function of<A extends readonly unknown[]>(...values: A): Observable<ValueFromArray<A>>;
//# sourceMappingURL=of.d.ts.map"""

    private val observableCombineLatestDts = """
import { Observable } from '../Observable';
import { ObservableInput, SchedulerLike, ObservedValueOf, ObservableInputTuple } from '../types';
import { Subscriber } from '../Subscriber';
import { AnyCatcher } from '../AnyCatcher';
/**
 * You have passed `any` here, we can't figure out if it is
 * an array or an object, so you're getting `unknown`. Use better types.
 * @param arg Something typed as `any`
 */
export declare function combineLatest<T extends AnyCatcher>(arg: T): Observable<unknown>;
export declare function combineLatest(sources: []): Observable<never>;
export declare function combineLatest<A extends readonly unknown[]>(sources: readonly [...ObservableInputTuple<A>]): Observable<A>;
/** @deprecated The `scheduler` parameter will be removed in v8. Use `scheduled` and `combineLatestAll`. Details: https://rxjs.dev/deprecations/scheduler-argument */
export declare function combineLatest<A extends readonly unknown[], R>(sources: readonly [...ObservableInputTuple<A>], resultSelector: (...values: A) => R, scheduler: SchedulerLike): Observable<R>;
export declare function combineLatest<A extends readonly unknown[], R>(sources: readonly [...ObservableInputTuple<A>], resultSelector: (...values: A) => R): Observable<R>;
/** @deprecated The `scheduler` parameter will be removed in v8. Use `scheduled` and `combineLatestAll`. Details: https://rxjs.dev/deprecations/scheduler-argument */
export declare function combineLatest<A extends readonly unknown[]>(sources: readonly [...ObservableInputTuple<A>], scheduler: SchedulerLike): Observable<A>;
/** @deprecated Pass an array of sources instead. The rest-parameters signature will be removed in v8. Details: https://rxjs.dev/deprecations/array-argument */
export declare function combineLatest<A extends readonly unknown[]>(...sources: [...ObservableInputTuple<A>]): Observable<A>;
/** @deprecated The `scheduler` parameter will be removed in v8. Use `scheduled` and `combineLatestAll`. Details: https://rxjs.dev/deprecations/scheduler-argument */
export declare function combineLatest<A extends readonly unknown[], R>(...sourcesAndResultSelectorAndScheduler: [...ObservableInputTuple<A>, (...values: A) => R, SchedulerLike]): Observable<R>;
/** @deprecated Pass an array of sources instead. The rest-parameters signature will be removed in v8. Details: https://rxjs.dev/deprecations/array-argument */
export declare function combineLatest<A extends readonly unknown[], R>(...sourcesAndResultSelector: [...ObservableInputTuple<A>, (...values: A) => R]): Observable<R>;
/** @deprecated The `scheduler` parameter will be removed in v8. Use `scheduled` and `combineLatestAll`. Details: https://rxjs.dev/deprecations/scheduler-argument */
export declare function combineLatest<A extends readonly unknown[]>(...sourcesAndScheduler: [...ObservableInputTuple<A>, SchedulerLike]): Observable<A>;
export declare function combineLatest(sourcesObject: {
    [K in any]: never;
}): Observable<never>;
export declare function combineLatest<T extends Record<string, ObservableInput<any>>>(sourcesObject: T): Observable<{
    [K in keyof T]: ObservedValueOf<T[K]>;
}>;
export declare function combineLatestInit(observables: ObservableInput<any>[], scheduler?: SchedulerLike, valueTransform?: (values: any[]) => any): (subscriber: Subscriber<any>) => void;
//# sourceMappingURL=combineLatest.d.ts.map"""

    private val observableForkJoinDts = """
import { Observable } from '../Observable';
import { ObservedValueOf, ObservableInputTuple, ObservableInput } from '../types';
import { AnyCatcher } from '../AnyCatcher';
/**
 * You have passed `any` here, we can't figure out if it is
 * an array or an object, so you're getting `unknown`. Use better types.
 * @param arg Something typed as `any`
 */
export declare function forkJoin<T extends AnyCatcher>(arg: T): Observable<unknown>;
export declare function forkJoin(scheduler: null | undefined): Observable<never>;
export declare function forkJoin(sources: readonly []): Observable<never>;
export declare function forkJoin<A extends readonly unknown[]>(sources: readonly [...ObservableInputTuple<A>]): Observable<A>;
export declare function forkJoin<A extends readonly unknown[], R>(sources: readonly [...ObservableInputTuple<A>], resultSelector: (...values: A) => R): Observable<R>;
/** @deprecated Pass an array of sources instead. The rest-parameters signature will be removed in v8. Details: https://rxjs.dev/deprecations/array-argument */
export declare function forkJoin<A extends readonly unknown[]>(...sources: [...ObservableInputTuple<A>]): Observable<A>;
/** @deprecated Pass an array of sources instead. The rest-parameters signature will be removed in v8. Details: https://rxjs.dev/deprecations/array-argument */
export declare function forkJoin<A extends readonly unknown[], R>(...sourcesAndResultSelector: [...ObservableInputTuple<A>, (...values: A) => R]): Observable<R>;
export declare function forkJoin(sourcesObject: {
    [K in any]: never;
}): Observable<never>;
export declare function forkJoin<T extends Record<string, ObservableInput<any>>>(sourcesObject: T): Observable<{
    [K in keyof T]: ObservedValueOf<T[K]>;
}>;
//# sourceMappingURL=forkJoin.d.ts.map"""

    private val observablePairsDts = """
import { Observable } from '../Observable';
import { SchedulerLike } from '../types';
/**
 * @deprecated Use `from(Object.entries(obj))` instead. Will be removed in v8.
 */
export declare function pairs<T>(arr: readonly T[], scheduler?: SchedulerLike): Observable<[string, T]>;
/**
 * @deprecated Use `from(Object.entries(obj))` instead. Will be removed in v8.
 */
export declare function pairs<O extends Record<string, unknown>>(obj: O, scheduler?: SchedulerLike): Observable<[keyof O, O[keyof O]]>;
/**
 * @deprecated Use `from(Object.entries(obj))` instead. Will be removed in v8.
 */
export declare function pairs<T>(iterable: Iterable<T>, scheduler?: SchedulerLike): Observable<[string, T]>;
/**
 * @deprecated Use `from(Object.entries(obj))` instead. Will be removed in v8.
 */
export declare function pairs(n: number | bigint | boolean | ((...args: any[]) => any) | symbol, scheduler?: SchedulerLike): Observable<[never, never]>;
//# sourceMappingURL=pairs.d.ts.map"""

    private val operatorsTimeoutDts = """
import { MonoTypeOperatorFunction, SchedulerLike, OperatorFunction, ObservableInput, ObservedValueOf } from '../types';
export interface TimeoutConfig<T, O extends ObservableInput<unknown> = ObservableInput<T>, M = unknown> {
    /**
     * The time allowed between values from the source before timeout is triggered.
     */
    each?: number;
    /**
     * The relative time as a `number` in milliseconds, or a specific time as a `Date` object,
     * by which the first value must arrive from the source before timeout is triggered.
     */
    first?: number | Date;
    /**
     * The scheduler to use with time-related operations within this operator. Defaults to {@link asyncScheduler}
     */
    scheduler?: SchedulerLike;
    /**
     * A factory used to create observable to switch to when timeout occurs. Provides
     * a {@link TimeoutInfo} about the source observable's emissions and what delay or
     * exact time triggered the timeout.
     */
    with?: (info: TimeoutInfo<T, M>) => O;
    /**
     * Optional additional metadata you can provide to code that handles
     * the timeout, will be provided through the {@link TimeoutError}.
     * This can be used to help identify the source of a timeout or pass along
     * other information related to the timeout.
     */
    meta?: M;
}
export interface TimeoutInfo<T, M = unknown> {
    /** Optional metadata that was provided to the timeout configuration. */
    readonly meta: M;
    /** The number of messages seen before the timeout */
    readonly seen: number;
    /** The last message seen */
    readonly lastValue: T | null;
}
/**
 * An error emitted when a timeout occurs.
 */
export interface TimeoutError<T = unknown, M = unknown> extends Error {
    /**
     * The information provided to the error by the timeout
     * operation that created the error. Will be `null` if
     * used directly in non-RxJS code with an empty constructor.
     * (Note that using this constructor directly is not recommended,
     * you should create your own errors)
     */
    info: TimeoutInfo<T, M> | null;
}
export interface TimeoutErrorCtor {
    /**
     * @deprecated Internal implementation detail. Do not construct error instances.
     * Cannot be tagged as internal: https://github.com/ReactiveX/rxjs/issues/6269
     */
    new <T = unknown, M = unknown>(info?: TimeoutInfo<T, M>): TimeoutError<T, M>;
}
/**
 * An error thrown by the {@link timeout} operator.
 *
 * Provided so users can use as a type and do quality comparisons.
 * We recommend you do not subclass this or create instances of this class directly.
 * If you have need of a error representing a timeout, you should
 * create your own error class and use that.
 *
 * @see {@link timeout}
 */
export declare const TimeoutError: TimeoutErrorCtor;
/**
 * If `with` is provided, this will return an observable that will switch to a different observable if the source
 * does not push values within the specified time parameters.
 *
 * <span class="informal">The most flexible option for creating a timeout behavior.</span>
 *
 * The first thing to know about the configuration is if you do not provide a `with` property to the configuration,
 * when timeout conditions are met, this operator will emit a {@link TimeoutError}. Otherwise, it will use the factory
 * function provided by `with`, and switch your subscription to the result of that. Timeout conditions are provided by
 * the settings in `first` and `each`.
 *
 * The `first` property can be either a `Date` for a specific time, a `number` for a time period relative to the
 * point of subscription, or it can be skipped. This property is to check timeout conditions for the arrival of
 * the first value from the source _only_. The timings of all subsequent values  from the source will be checked
 * against the time period provided by `each`, if it was provided.
 *
 * The `each` property can be either a `number` or skipped. If a value for `each` is provided, it represents the amount of
 * time the resulting observable will wait between the arrival of values from the source before timing out. Note that if
 * `first` is _not_ provided, the value from `each` will be used to check timeout conditions for the arrival of the first
 * value and all subsequent values. If `first` _is_ provided, `each` will only be use to check all values after the first.
 *
 * ## Examples
 *
 * Emit a custom error if there is too much time between values
 *
 * ```ts
 * import { interval, timeout, throwError } from 'rxjs';
 *
 * class CustomTimeoutError extends Error {
 *   constructor() {
 *     super('It was too slow');
 *     this.name = 'CustomTimeoutError';
 *   }
 * }
 *
 * const slow${'$'} = interval(900);
 *
 * slow${'$'}.pipe(
 *   timeout({
 *     each: 1000,
 *     with: () => throwError(() => new CustomTimeoutError())
 *   })
 * )
 * .subscribe({
 *   error: console.error
 * });
 * ```
 *
 * Switch to a faster observable if your source is slow.
 *
 * ```ts
 * import { interval, timeout } from 'rxjs';
 *
 * const slow${'$'} = interval(900);
 * const fast${'$'} = interval(500);
 *
 * slow${'$'}.pipe(
 *   timeout({
 *     each: 1000,
 *     with: () => fast${'$'},
 *   })
 * )
 * .subscribe(console.log);
 * ```
 * @param config The configuration for the timeout.
 */
export declare function timeout<T, O extends ObservableInput<unknown>, M = unknown>(config: TimeoutConfig<T, O, M> & {
    with: (info: TimeoutInfo<T, M>) => O;
}): OperatorFunction<T, T | ObservedValueOf<O>>;
/**
 * Returns an observable that will error or switch to a different observable if the source does not push values
 * within the specified time parameters.
 *
 * <span class="informal">The most flexible option for creating a timeout behavior.</span>
 *
 * The first thing to know about the configuration is if you do not provide a `with` property to the configuration,
 * when timeout conditions are met, this operator will emit a {@link TimeoutError}. Otherwise, it will use the factory
 * function provided by `with`, and switch your subscription to the result of that. Timeout conditions are provided by
 * the settings in `first` and `each`.
 *
 * The `first` property can be either a `Date` for a specific time, a `number` for a time period relative to the
 * point of subscription, or it can be skipped. This property is to check timeout conditions for the arrival of
 * the first value from the source _only_. The timings of all subsequent values  from the source will be checked
 * against the time period provided by `each`, if it was provided.
 *
 * The `each` property can be either a `number` or skipped. If a value for `each` is provided, it represents the amount of
 * time the resulting observable will wait between the arrival of values from the source before timing out. Note that if
 * `first` is _not_ provided, the value from `each` will be used to check timeout conditions for the arrival of the first
 * value and all subsequent values. If `first` _is_ provided, `each` will only be use to check all values after the first.
 *
 * ### Handling TimeoutErrors
 *
 * If no `with` property was provided, subscriptions to the resulting observable may emit an error of {@link TimeoutError}.
 * The timeout error provides useful information you can examine when you're handling the error. The most common way to handle
 * the error would be with {@link catchError}, although you could use {@link tap} or just the error handler in your `subscribe` call
 * directly, if your error handling is only a side effect (such as notifying the user, or logging).
 *
 * In this case, you would check the error for `instanceof TimeoutError` to validate that the error was indeed from `timeout`, and
 * not from some other source. If it's not from `timeout`, you should probably rethrow it if you're in a `catchError`.
 *
 * ## Examples
 *
 * Emit a {@link TimeoutError} if the first value, and _only_ the first value, does not arrive within 5 seconds
 *
 * ```ts
 * import { interval, timeout } from 'rxjs';
 *
 * // A random interval that lasts between 0 and 10 seconds per tick
 * const source${'$'} = interval(Math.round(Math.random() * 10_000));
 *
 * source${'$'}.pipe(
 *   timeout({ first: 5_000 })
 * )
 * .subscribe({
 *   next: console.log,
 *   error: console.error
 * });
 * ```
 *
 * Emit a {@link TimeoutError} if the source waits longer than 5 seconds between any two values or the first value
 * and subscription.
 *
 * ```ts
 * import { timer, timeout, expand } from 'rxjs';
 *
 * const getRandomTime = () => Math.round(Math.random() * 10_000);
 *
 * // An observable that waits a random amount of time between each delivered value
 * const source${'$'} = timer(getRandomTime())
 *   .pipe(expand(() => timer(getRandomTime())));
 *
 * source${'$'}
 *   .pipe(timeout({ each: 5_000 }))
 *   .subscribe({
 *     next: console.log,
 *     error: console.error
 *   });
 * ```
 *
 * Emit a {@link TimeoutError} if the source does not emit before 7 seconds, _or_ if the source waits longer than
 * 5 seconds between any two values after the first.
 *
 * ```ts
 * import { timer, timeout, expand } from 'rxjs';
 *
 * const getRandomTime = () => Math.round(Math.random() * 10_000);
 *
 * // An observable that waits a random amount of time between each delivered value
 * const source${'$'} = timer(getRandomTime())
 *   .pipe(expand(() => timer(getRandomTime())));
 *
 * source${'$'}
 *   .pipe(timeout({ first: 7_000, each: 5_000 }))
 *   .subscribe({
 *     next: console.log,
 *     error: console.error
 *   });
 * ```
 */
export declare function timeout<T, M = unknown>(config: Omit<TimeoutConfig<T, any, M>, 'with'>): OperatorFunction<T, T>;
/**
 * Returns an observable that will error if the source does not push its first value before the specified time passed as a `Date`.
 * This is functionally the same as `timeout({ first: someDate })`.
 *
 * <span class="informal">Errors if the first value doesn't show up before the given date and time</span>
 *
 * ![](timeout.png)
 *
 * @param first The date to at which the resulting observable will timeout if the source observable
 * does not emit at least one value.
 * @param scheduler The scheduler to use. Defaults to {@link asyncScheduler}.
 */
export declare function timeout<T>(first: Date, scheduler?: SchedulerLike): MonoTypeOperatorFunction<T>;
/**
 * Returns an observable that will error if the source does not push a value within the specified time in milliseconds.
 * This is functionally the same as `timeout({ each: milliseconds })`.
 *
 * <span class="informal">Errors if it waits too long between any value</span>
 *
 * ![](timeout.png)
 *
 * @param each The time allowed between each pushed value from the source before the resulting observable
 * will timeout.
 * @param scheduler The scheduler to use. Defaults to {@link asyncScheduler}.
 */
export declare function timeout<T>(each: number, scheduler?: SchedulerLike): MonoTypeOperatorFunction<T>;
//# sourceMappingURL=timeout.d.ts.map"""

    private val utilArgumentOutOfRangeErrorDts = """
export interface ArgumentOutOfRangeError extends Error {
}
export interface ArgumentOutOfRangeErrorCtor {
    /**
     * @deprecated Internal implementation detail. Do not construct error instances.
     * Cannot be tagged as internal: https://github.com/ReactiveX/rxjs/issues/6269
     */
    new (): ArgumentOutOfRangeError;
}
/**
 * An error thrown when an element was queried at a certain index of an
 * Observable, but no such index or position exists in that sequence.
 *
 * @see {@link elementAt}
 * @see {@link take}
 * @see {@link takeLast}
 */
export declare const ArgumentOutOfRangeError: ArgumentOutOfRangeErrorCtor;
//# sourceMappingURL=ArgumentOutOfRangeError.d.ts.map"""

    private val utilEmptyErrorDts = """
export interface EmptyError extends Error {
}
export interface EmptyErrorCtor {
    /**
     * @deprecated Internal implementation detail. Do not construct error instances.
     * Cannot be tagged as internal: https://github.com/ReactiveX/rxjs/issues/6269
     */
    new (): EmptyError;
}
/**
 * An error thrown when an Observable or a sequence was queried but has no
 * elements.
 *
 * @see {@link first}
 * @see {@link last}
 * @see {@link single}
 * @see {@link firstValueFrom}
 * @see {@link lastValueFrom}
 */
export declare const EmptyError: EmptyErrorCtor;
//# sourceMappingURL=EmptyError.d.ts.map"""

    /**
     * (EXT.16) The package's `types` entry, `dist/types/index.d.ts` of
     * `rxjs@7.8.2`, verbatim — the file whose re-export graph is the
     * public surface the wiring binds. Most of its lines name files this
     * rung's fixture does not carry, and each of those is a loud marker.
     */
    private val rxjsIndexDts = """
/// <reference path="operators/index.d.ts" />
/// <reference path="testing/index.d.ts" />
export { Observable } from './internal/Observable';
export { ConnectableObservable } from './internal/observable/ConnectableObservable';
export { GroupedObservable } from './internal/operators/groupBy';
export { Operator } from './internal/Operator';
export { observable } from './internal/symbol/observable';
export { animationFrames } from './internal/observable/dom/animationFrames';
export { Subject } from './internal/Subject';
export { BehaviorSubject } from './internal/BehaviorSubject';
export { ReplaySubject } from './internal/ReplaySubject';
export { AsyncSubject } from './internal/AsyncSubject';
export { asap, asapScheduler } from './internal/scheduler/asap';
export { async, asyncScheduler } from './internal/scheduler/async';
export { queue, queueScheduler } from './internal/scheduler/queue';
export { animationFrame, animationFrameScheduler } from './internal/scheduler/animationFrame';
export { VirtualTimeScheduler, VirtualAction } from './internal/scheduler/VirtualTimeScheduler';
export { Scheduler } from './internal/Scheduler';
export { Subscription } from './internal/Subscription';
export { Subscriber } from './internal/Subscriber';
export { Notification, NotificationKind } from './internal/Notification';
export { pipe } from './internal/util/pipe';
export { noop } from './internal/util/noop';
export { identity } from './internal/util/identity';
export { isObservable } from './internal/util/isObservable';
export { lastValueFrom } from './internal/lastValueFrom';
export { firstValueFrom } from './internal/firstValueFrom';
export { ArgumentOutOfRangeError } from './internal/util/ArgumentOutOfRangeError';
export { EmptyError } from './internal/util/EmptyError';
export { NotFoundError } from './internal/util/NotFoundError';
export { ObjectUnsubscribedError } from './internal/util/ObjectUnsubscribedError';
export { SequenceError } from './internal/util/SequenceError';
export { TimeoutError } from './internal/operators/timeout';
export { UnsubscriptionError } from './internal/util/UnsubscriptionError';
export { bindCallback } from './internal/observable/bindCallback';
export { bindNodeCallback } from './internal/observable/bindNodeCallback';
export { combineLatest } from './internal/observable/combineLatest';
export { concat } from './internal/observable/concat';
export { connectable } from './internal/observable/connectable';
export { defer } from './internal/observable/defer';
export { empty } from './internal/observable/empty';
export { forkJoin } from './internal/observable/forkJoin';
export { from } from './internal/observable/from';
export { fromEvent } from './internal/observable/fromEvent';
export { fromEventPattern } from './internal/observable/fromEventPattern';
export { generate } from './internal/observable/generate';
export { iif } from './internal/observable/iif';
export { interval } from './internal/observable/interval';
export { merge } from './internal/observable/merge';
export { never } from './internal/observable/never';
export { of } from './internal/observable/of';
export { onErrorResumeNext } from './internal/observable/onErrorResumeNext';
export { pairs } from './internal/observable/pairs';
export { partition } from './internal/observable/partition';
export { race } from './internal/observable/race';
export { range } from './internal/observable/range';
export { throwError } from './internal/observable/throwError';
export { timer } from './internal/observable/timer';
export { using } from './internal/observable/using';
export { zip } from './internal/observable/zip';
export { scheduled } from './internal/scheduled/scheduled';
export { EMPTY } from './internal/observable/empty';
export { NEVER } from './internal/observable/never';
export * from './internal/types';
export { config, GlobalConfig } from './internal/config';
export { audit } from './internal/operators/audit';
export { auditTime } from './internal/operators/auditTime';
export { buffer } from './internal/operators/buffer';
export { bufferCount } from './internal/operators/bufferCount';
export { bufferTime } from './internal/operators/bufferTime';
export { bufferToggle } from './internal/operators/bufferToggle';
export { bufferWhen } from './internal/operators/bufferWhen';
export { catchError } from './internal/operators/catchError';
export { combineAll } from './internal/operators/combineAll';
export { combineLatestAll } from './internal/operators/combineLatestAll';
export { combineLatestWith } from './internal/operators/combineLatestWith';
export { concatAll } from './internal/operators/concatAll';
export { concatMap } from './internal/operators/concatMap';
export { concatMapTo } from './internal/operators/concatMapTo';
export { concatWith } from './internal/operators/concatWith';
export { connect, ConnectConfig } from './internal/operators/connect';
export { count } from './internal/operators/count';
export { debounce } from './internal/operators/debounce';
export { debounceTime } from './internal/operators/debounceTime';
export { defaultIfEmpty } from './internal/operators/defaultIfEmpty';
export { delay } from './internal/operators/delay';
export { delayWhen } from './internal/operators/delayWhen';
export { dematerialize } from './internal/operators/dematerialize';
export { distinct } from './internal/operators/distinct';
export { distinctUntilChanged } from './internal/operators/distinctUntilChanged';
export { distinctUntilKeyChanged } from './internal/operators/distinctUntilKeyChanged';
export { elementAt } from './internal/operators/elementAt';
export { endWith } from './internal/operators/endWith';
export { every } from './internal/operators/every';
export { exhaust } from './internal/operators/exhaust';
export { exhaustAll } from './internal/operators/exhaustAll';
export { exhaustMap } from './internal/operators/exhaustMap';
export { expand } from './internal/operators/expand';
export { filter } from './internal/operators/filter';
export { finalize } from './internal/operators/finalize';
export { find } from './internal/operators/find';
export { findIndex } from './internal/operators/findIndex';
export { first } from './internal/operators/first';
export { groupBy, BasicGroupByOptions, GroupByOptionsWithElement } from './internal/operators/groupBy';
export { ignoreElements } from './internal/operators/ignoreElements';
export { isEmpty } from './internal/operators/isEmpty';
export { last } from './internal/operators/last';
export { map } from './internal/operators/map';
export { mapTo } from './internal/operators/mapTo';
export { materialize } from './internal/operators/materialize';
export { max } from './internal/operators/max';
export { mergeAll } from './internal/operators/mergeAll';
export { flatMap } from './internal/operators/flatMap';
export { mergeMap } from './internal/operators/mergeMap';
export { mergeMapTo } from './internal/operators/mergeMapTo';
export { mergeScan } from './internal/operators/mergeScan';
export { mergeWith } from './internal/operators/mergeWith';
export { min } from './internal/operators/min';
export { multicast } from './internal/operators/multicast';
export { observeOn } from './internal/operators/observeOn';
export { onErrorResumeNextWith } from './internal/operators/onErrorResumeNextWith';
export { pairwise } from './internal/operators/pairwise';
export { pluck } from './internal/operators/pluck';
export { publish } from './internal/operators/publish';
export { publishBehavior } from './internal/operators/publishBehavior';
export { publishLast } from './internal/operators/publishLast';
export { publishReplay } from './internal/operators/publishReplay';
export { raceWith } from './internal/operators/raceWith';
export { reduce } from './internal/operators/reduce';
export { repeat, RepeatConfig } from './internal/operators/repeat';
export { repeatWhen } from './internal/operators/repeatWhen';
export { retry, RetryConfig } from './internal/operators/retry';
export { retryWhen } from './internal/operators/retryWhen';
export { refCount } from './internal/operators/refCount';
export { sample } from './internal/operators/sample';
export { sampleTime } from './internal/operators/sampleTime';
export { scan } from './internal/operators/scan';
export { sequenceEqual } from './internal/operators/sequenceEqual';
export { share, ShareConfig } from './internal/operators/share';
export { shareReplay, ShareReplayConfig } from './internal/operators/shareReplay';
export { single } from './internal/operators/single';
export { skip } from './internal/operators/skip';
export { skipLast } from './internal/operators/skipLast';
export { skipUntil } from './internal/operators/skipUntil';
export { skipWhile } from './internal/operators/skipWhile';
export { startWith } from './internal/operators/startWith';
export { subscribeOn } from './internal/operators/subscribeOn';
export { switchAll } from './internal/operators/switchAll';
export { switchMap } from './internal/operators/switchMap';
export { switchMapTo } from './internal/operators/switchMapTo';
export { switchScan } from './internal/operators/switchScan';
export { take } from './internal/operators/take';
export { takeLast } from './internal/operators/takeLast';
export { takeUntil } from './internal/operators/takeUntil';
export { takeWhile } from './internal/operators/takeWhile';
export { tap, TapObserver } from './internal/operators/tap';
export { throttle, ThrottleConfig } from './internal/operators/throttle';
export { throttleTime } from './internal/operators/throttleTime';
export { throwIfEmpty } from './internal/operators/throwIfEmpty';
export { timeInterval } from './internal/operators/timeInterval';
export { timeout, TimeoutConfig, TimeoutInfo } from './internal/operators/timeout';
export { timeoutWith } from './internal/operators/timeoutWith';
export { timestamp } from './internal/operators/timestamp';
export { toArray } from './internal/operators/toArray';
export { window } from './internal/operators/window';
export { windowCount } from './internal/operators/windowCount';
export { windowTime } from './internal/operators/windowTime';
export { windowToggle } from './internal/operators/windowToggle';
export { windowWhen } from './internal/operators/windowWhen';
export { withLatestFrom } from './internal/operators/withLatestFrom';
export { zipAll } from './internal/operators/zipAll';
export { zipWith } from './internal/operators/zipWith';
//# sourceMappingURL=index.d.ts.map"""

    private val rxjsExtras: List<SourceFileEntry> = listOf(
        SourceFileEntry("/rxjs/index.d.ts", rxjsIndexDts),
        SourceFileEntry("/rxjs/internal/types.d.ts", typesDts),
        SourceFileEntry("/rxjs/internal/Observable.d.ts", observableDts),
        SourceFileEntry("/rxjs/internal/Subject.d.ts", subjectDts),
        SourceFileEntry("/rxjs/internal/Subscription.d.ts", subscriptionDts),
        SourceFileEntry("/rxjs/internal/Subscriber.d.ts", subscriberDts),
        SourceFileEntry("/rxjs/internal/Operator.d.ts", operatorDts),
        SourceFileEntry("/rxjs/internal/AnyCatcher.d.ts", anyCatcherDts),
        SourceFileEntry("/rxjs/internal/ajax/types.d.ts", ajaxTypesDts),
        SourceFileEntry("/rxjs/internal/ajax/errors.d.ts", ajaxErrorsDts),
        SourceFileEntry("/rxjs/internal/observable/ConnectableObservable.d.ts", observableConnectableObservableDts),
        SourceFileEntry("/rxjs/internal/observable/zip.d.ts", observableZipDts),
        SourceFileEntry("/rxjs/internal/operators/zip.d.ts", operatorsZipDts),
        SourceFileEntry("/rxjs/internal/operators/first.d.ts", operatorsFirstDts),
        SourceFileEntry("/rxjs/internal/operators/last.d.ts", operatorsLastDts),
        SourceFileEntry("/rxjs/internal/observable/of.d.ts", observableOfDts),
        SourceFileEntry("/rxjs/internal/observable/combineLatest.d.ts", observableCombineLatestDts),
        SourceFileEntry("/rxjs/internal/observable/forkJoin.d.ts", observableForkJoinDts),
        SourceFileEntry("/rxjs/internal/observable/pairs.d.ts", observablePairsDts),
        SourceFileEntry("/rxjs/internal/operators/timeout.d.ts", operatorsTimeoutDts),
        SourceFileEntry("/rxjs/internal/util/ArgumentOutOfRangeError.d.ts", utilArgumentOutOfRangeErrorDts),
        SourceFileEntry("/rxjs/internal/util/EmptyError.d.ts", utilEmptyErrorDts),
    )

    /** (EXT.16) Wired to the package: `rxjs`, entry `/rxjs/index.d.ts`. */
    internal fun generateRxjsExtras(): KotlinExternals =
        generateKotlinExternals(rxjsExtras, module = ModuleWiring("rxjs", "/rxjs/index.d.ts"))

    @Test
    fun `rxjs extras generate and the generated kotlin compiles`() {
        val result = generateRxjsExtras()
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        val errorCodes = result.errors.map { it.code }
        assert(compileErrors.isEmpty())
        assert(check.successful)
        // (EXT.16) The entry's two `/// <reference path>` lines name entries
        // this fixture does not carry: TS6053 twice, and nothing else.
        assert(errorCodes == listOf(6053, 6053))
    }

    @Test
    fun `the three mechanisms are visible - the value skip, the collapsed overloads, the narrowed var`() {
        val result = generateRxjsExtras()
        val rendered = result.kotlin
        // (2) (EXT.18) The companion value RENAMES under the wiring — `<Name>Value`
        // bound to `<Name>` by `@JsName` — and the TYPE survives under its
        // name, in every file that carries the idiom; the two ajax errors
        // live under the `rxjs/ajax` entry, so their internal-path marker
        // follows the rename marker. No value keeps the colliding name.
        val ajaxError = "public external interface AjaxError {\n" in rendered
        val ajaxErrorValue = "/* xtsc: value AjaxError renamed AjaxErrorValue - Kotlin cannot hold a value and a type of one name; bound by @JsName */\n" +
            "/* xtsc: value AjaxError is not exported by the package entry - an internal path a consumer cannot bind */\n" +
            "@JsName(\"AjaxError\")\n" +
            "public external val AjaxErrorValue: AjaxErrorCtor\n" in rendered
        val ajaxTimeoutValue = "/* xtsc: value AjaxTimeoutError renamed AjaxTimeoutErrorValue - Kotlin cannot hold a value and a type of one name; bound by @JsName */\n" +
            "/* xtsc: value AjaxTimeoutError is not exported by the package entry - an internal path a consumer cannot bind */\n" +
            "@JsName(\"AjaxTimeoutError\")\n" +
            "public external val AjaxTimeoutErrorValue: AjaxTimeoutErrorCtor\n" in rendered
        val timeoutValue = "/* xtsc: value TimeoutError renamed TimeoutErrorValue - Kotlin cannot hold a value and a type of one name; bound by @JsName */\n" +
            "@JsName(\"TimeoutError\")\n" +
            "public external val TimeoutErrorValue: TimeoutErrorCtor\n" in rendered
        val rangeValue = "/* xtsc: value ArgumentOutOfRangeError renamed ArgumentOutOfRangeErrorValue - Kotlin cannot hold a value and a type of one name; bound by @JsName */\n" +
            "@JsName(\"ArgumentOutOfRangeError\")\n" +
            "public external val ArgumentOutOfRangeErrorValue: ArgumentOutOfRangeErrorCtor\n" in rendered
        val emptyValue = "/* xtsc: value EmptyError renamed EmptyErrorValue - Kotlin cannot hold a value and a type of one name; bound by @JsName */\n" +
            "@JsName(\"EmptyError\")\n" +
            "public external val EmptyErrorValue: EmptyErrorCtor\n" in rendered
        val noValueAjaxError = "public external val AjaxError:" !in rendered
        val noValueSkips = "shares its name with the type" !in rendered
        // (1) `first`'s six overloads are four Kotlin signatures. (EXT.12):
        // the `null`-predicate first overload (three markers: a default not
        // carried, the `null` parameter, the return) is one overload with the
        // typed `<T, D>(predicate: BooleanConstructor, defaultValue: D)` (two
        // markers), which is now the survivor — before, first-wins kept the
        // `null` twin. The name-only twin of the fourth is a marker naming
        // it, at its position.
        val firstBlock = "/* xtsc: skipped overload of first collapsing to a duplicate signature - kept <T, D> first(predicate: Any?, defaultValue: D) */\n" +
            "\n" +
            "public external fun <T> first(predicate: Any? /* xtsc: unmapped BooleanConstructor */): Any? /* xtsc: unmapped OperatorFunction<any, any> */\n" +
            "\n" +
            "public external fun <T, D> first(predicate: Any? /* xtsc: unmapped BooleanConstructor */ = definedExternally, defaultValue: D = definedExternally): Any? /* xtsc: unmapped OperatorFunction<any, any> */\n" +
            "\n" +
            "/* xtsc: constraint on S: any not carried */\n" +
            "public external fun <T, S> first(predicate: (T, Double, Observable<T>) -> Boolean, defaultValue: S? = definedExternally): OperatorFunction<T, S>\n" +
            "\n" +
            "/* xtsc: constraint on S: any not carried */\n" +
            "public external fun <T, S, D> first(predicate: (T, Double, Observable<T>) -> Boolean, defaultValue: D): Any? /* xtsc: unmapped OperatorFunction<any, any> */\n" +
            "\n" +
            "/* xtsc: skipped overload of first collapsing to a duplicate signature - kept <T, S> first(predicate: (T, Double, Observable<T>) -> Boolean, defaultValue: S?) */\n"
        val first = firstBlock in rendered
        // The `of` twins. (EXT.12): `<T> of(value: T)` is one overload with
        // `<A> of(...valuesAndScheduler)`'s fallback (three markers) and with
        // `<A> of(...values: A)`'s (three) — the clean signature, declared
        // between them, is the survivor and both marked twins are markers
        // naming it, each where it was declared; before, the first-declared
        // fallback won and the clean signature was the marker. The
        // `null`/`undefined` pair is the tie control: equally marked, the
        // first stays.
        val ofValue = "public external fun of(value: Any? /* xtsc: unmapped null */): Any? /* xtsc: unmapped Observable<null> */\n" +
            "\n" +
            "/* xtsc: skipped overload of of collapsing to a duplicate signature - kept of(value: Any?) */\n" in rendered
        val ofCollapsed = "public external fun of(scheduler: SchedulerLike): Any? /* xtsc: unmapped Observable<never> */\n" +
            "\n" +
            "/* xtsc: skipped overload of of collapsing to a duplicate signature - kept <T> of(value: T) */\n" +
            "\n" +
            "public external fun of(): Any? /* xtsc: unmapped Observable<never> */\n" +
            "\n" +
            "public external fun <T> of(): Observable<T>\n" +
            "\n" +
            "public external fun <T> of(value: T): Observable<T>\n" +
            "\n" +
            "/* xtsc: skipped overload of of collapsing to a duplicate signature - kept <T> of(value: T) */\n" in rendered
        val noMarkedOf = "of(valuesAndScheduler" !in rendered
        // The cross-file `zip`: the operator's `<T, A> zip(otherInputs)` is
        // the name-only twin of the observable's `<A, R>
        // zip(sourcesAndResultSelector)` — two markers against three — and
        // is a marker naming it.
        val zipCollapsed = "/* xtsc: skipped overload of zip collapsing to a duplicate signature - kept <A, R> zip(sourcesAndResultSelector: Any?) */" in rendered
        val noZipOtherInputs = "public external fun <T, A> zip(otherInputs" !in rendered
        // (3) The narrowed var renders the inherited type with the marker.
        val narrowed = "public open external class ConnectableObservable<T>(source: Observable<T>, subjectFactory: () -> Subject<T>) : Observable<T> {\n" +
            "    /* xtsc: narrowed to Observable<T> in TypeScript - rendered as the inherited Observable<Any?>? */\n" +
            "    public override var source: Observable<Any?>?\n" +
            "    public fun connect(): Subscription\n" +
            "    public fun refCount(): Observable<T>\n" +
            "}\n"
        val narrowedVar = narrowed in rendered
        // (EXT.16) Wired to the package: the `zip` OPERATOR (`operators/zip`)
        // is exported by the `rxjs/operators` entry, not by `rxjs`, so both
        // its surviving overloads are internal paths here while the `zip`
        // OBSERVABLE (`observable/zip`) binds under its own name; the ajax
        // errors live under the `rxjs/ajax` entry the same way. Ten
        // internal paths in all — the eight of (EXT.16) plus the two
        // renamed ajax values — and (EXT.18) exactly the five renames'
        // `@JsName`s: rxjs re-exports nothing under another name.
        val header = rendered.startsWith("@file:JsModule(\"rxjs\")\n\n")
        val operatorZipInternal = "/* xtsc: function zip is not exported by the package entry - an internal path a consumer cannot bind */\npublic external fun <T, A, R> zip(otherInputsAndProject: Any? /* xtsc: unmapped [any] */, project: Any? /* xtsc: unmapped (...values: Cons<T, A>) => any */): OperatorFunction<T, R>\n" in rendered
        val observableZipBound = "/* xtsc: constraint on A: readonly unknown[] not carried */\npublic external fun <A> zip(sources: Any? /* xtsc: unmapped [any] */): Observable<A>\n" in rendered
        val internalPaths = Regex("not exported by the package entry").findAll(rendered).count() == 10
        val fiveJsNames = Regex("^@JsName\\(", RegexOption.MULTILINE).findAll(rendered).count() == 5
        assert(header)
        assert(operatorZipInternal)
        assert(observableZipBound)
        assert(internalPaths)
        assert(fiveJsNames)
        assert(ajaxError)
        assert(ajaxErrorValue)
        assert(ajaxTimeoutValue)
        assert(timeoutValue)
        assert(rangeValue)
        assert(emptyValue)
        assert(noValueAjaxError)
        assert(noValueSkips)
        assert(first)
        assert(ofValue)
        assert(ofCollapsed)
        assert(noMarkedOf)
        assert(zipCollapsed)
        assert(noZipOtherInputs)
        assert(narrowedVar)
    }

    @Test
    fun `rxjs extras' inexpressible shapes stay loud - never silent`() {
        val result = generateRxjsExtras()
        val rendered = result.kotlin
        // The tuple-typed sources, the construct signatures behind the
        // companion values and the `typeof` re-routes keep their markers;
        // nothing collapsed silently into a plain `Any?` parameter.
        val tupleSources = "public external fun <A> zip(sources: Any? /* xtsc: unmapped [any] */): Observable<A>\n" in rendered
        val constructSignature = "public external interface AjaxErrorCtor {\n    /* xtsc: skipped construct signature */\n}\n" in rendered
        val booleanConstructor = "predicate: Any? /* xtsc: unmapped BooleanConstructor */" in rendered
        // (EXT.12) The `null`-predicate `first` is the collapse's loser and
        // says so — a marker naming its survivor, never a silent drop.
        val nullPredicate = "/* xtsc: skipped overload of first collapsing to a duplicate signature - kept <T, D> first(predicate: Any?, defaultValue: D) */\n" in rendered
        val noNamelessFun = "fun ``(" !in rendered
        assert(tupleSources)
        assert(constructSignature)
        assert(booleanConstructor)
        assert(nullPredicate)
        assert(noNamelessFun)
    }

}
