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
 * (EXT.11a) THE THIRD FIXTURE-LADDER RUNG: the generator over the REAL
 * `rxjs@7.8.2` CORE type declarations — FIFTEEN files under
 * `dist/types/internal` — gated by the metadata compile.
 *
 * The fixtures below are the verbatim declaration files of the `rxjs` npm
 * package, version 7.8.2 — Apache License 2.0, Copyright (c) 2015-2018 Google,
 * Inc., Netflix, Inc., Microsoft Corp. and contributors
 * (https://github.com/reactivex/rxjs) — embedded here as test INPUT under the
 * licence's terms: this notice retains the work's copyright statement, the
 * licence identifier (`SPDX-License-Identifier: Apache-2.0`) and the pointer
 * to the licence text (https://www.apache.org/licenses/LICENSE-2.0), which is
 * what a redistribution of an unmodified Apache-licensed source excerpt owes.
 * It is the ladder's third rung because it is what smol-toml is not: a
 * library whose SPINE is function-typed — `UnaryFunction`/`OperatorFunction`/
 * `MonoTypeOperatorFunction` are interfaces with ONE CALL SIGNATURE and empty
 * extensions of one, i.e. Kotlin function-type aliases — with `this`-typed
 * callbacks (`(this: SchedulerAction<T>, state: T) => void`), a `typeof
 * Action` constructor parameter, class hierarchies four deep across files
 * (`AsyncSubject` -> `Subject` -> `Observable` -> `Subscribable`), a
 * string-valued enum, a symbol-keyed member and a dozen conditional and
 * mapped aliases that stay loud.
 *
 * (EXT.16) Generated WIRED to the package — `ModuleWiring("rxjs",
 * "/rxjs/index.d.ts")`, the verbatim `dist/types/index.d.ts` entry added
 * to the fixture — so the real output carries `@file:JsModule("rxjs")`,
 * the internal paths the entry does not reach are loud, and the entry's
 * re-exports of files outside this fixture are loud at the statement; the
 * gate compiles the annotation-free variant.
 */
class KotlinExternalsRxjsGateTest {

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

    private val schedulerDts = """
import { Action } from './scheduler/Action';
import { Subscription } from './Subscription';
import { SchedulerLike, SchedulerAction } from './types';
/**
 * An execution context and a data structure to order tasks and schedule their
 * execution. Provides a notion of (potentially virtual) time, through the
 * `now()` getter method.
 *
 * Each unit of work in a Scheduler is called an `Action`.
 *
 * ```ts
 * class Scheduler {
 *   now(): number;
 *   schedule(work, delay?, state?): Subscription;
 * }
 * ```
 *
 * @deprecated Scheduler is an internal implementation detail of RxJS, and
 * should not be used directly. Rather, create your own class and implement
 * {@link SchedulerLike}. Will be made internal in v8.
 */
export declare class Scheduler implements SchedulerLike {
    private schedulerActionCtor;
    static now: () => number;
    constructor(schedulerActionCtor: typeof Action, now?: () => number);
    /**
     * A getter method that returns a number representing the current time
     * (at the time this function was called) according to the scheduler's own
     * internal clock.
     * @return A number that represents the current time. May or may not
     * have a relation to wall-clock time. May or may not refer to a time unit
     * (e.g. milliseconds).
     */
    now: () => number;
    /**
     * Schedules a function, `work`, for execution. May happen at some point in
     * the future, according to the `delay` parameter, if specified. May be passed
     * some context object, `state`, which will be passed to the `work` function.
     *
     * The given arguments will be processed an stored as an Action object in a
     * queue of actions.
     *
     * @param work A function representing a task, or some unit of work to be
     * executed by the Scheduler.
     * @param delay Time to wait before executing the work, where the time unit is
     * implicit and defined by the Scheduler itself.
     * @param state Some contextual data that the `work` function uses when called
     * by the Scheduler.
     * @return A subscription in order to be able to unsubscribe the scheduled work.
     */
    schedule<T>(work: (this: SchedulerAction<T>, state?: T) => void, delay?: number, state?: T): Subscription;
}
//# sourceMappingURL=Scheduler.d.ts.map"""

    private val behaviorSubjectDts = """
import { Subject } from './Subject';
/**
 * A variant of Subject that requires an initial value and emits its current
 * value whenever it is subscribed to.
 */
export declare class BehaviorSubject<T> extends Subject<T> {
    private _value;
    constructor(_value: T);
    get value(): T;
    getValue(): T;
    next(value: T): void;
}
//# sourceMappingURL=BehaviorSubject.d.ts.map"""

    private val replaySubjectDts = """
import { Subject } from './Subject';
import { TimestampProvider } from './types';
/**
 * A variant of {@link Subject} that "replays" old values to new subscribers by emitting them when they first subscribe.
 *
 * `ReplaySubject` has an internal buffer that will store a specified number of values that it has observed. Like `Subject`,
 * `ReplaySubject` "observes" values by having them passed to its `next` method. When it observes a value, it will store that
 * value for a time determined by the configuration of the `ReplaySubject`, as passed to its constructor.
 *
 * When a new subscriber subscribes to the `ReplaySubject` instance, it will synchronously emit all values in its buffer in
 * a First-In-First-Out (FIFO) manner. The `ReplaySubject` will also complete, if it has observed completion; and it will
 * error if it has observed an error.
 *
 * There are two main configuration items to be concerned with:
 *
 * 1. `bufferSize` - This will determine how many items are stored in the buffer, defaults to infinite.
 * 2. `windowTime` - The amount of time to hold a value in the buffer before removing it from the buffer.
 *
 * Both configurations may exist simultaneously. So if you would like to buffer a maximum of 3 values, as long as the values
 * are less than 2 seconds old, you could do so with a `new ReplaySubject(3, 2000)`.
 *
 * ### Differences with BehaviorSubject
 *
 * `BehaviorSubject` is similar to `new ReplaySubject(1)`, with a couple of exceptions:
 *
 * 1. `BehaviorSubject` comes "primed" with a single value upon construction.
 * 2. `ReplaySubject` will replay values, even after observing an error, where `BehaviorSubject` will not.
 *
 * @see {@link Subject}
 * @see {@link BehaviorSubject}
 * @see {@link shareReplay}
 */
export declare class ReplaySubject<T> extends Subject<T> {
    private _bufferSize;
    private _windowTime;
    private _timestampProvider;
    private _buffer;
    private _infiniteTimeWindow;
    /**
     * @param _bufferSize The size of the buffer to replay on subscription
     * @param _windowTime The amount of time the buffered items will stay buffered
     * @param _timestampProvider An object with a `now()` method that provides the current timestamp. This is used to
     * calculate the amount of time something has been buffered.
     */
    constructor(_bufferSize?: number, _windowTime?: number, _timestampProvider?: TimestampProvider);
    next(value: T): void;
    private _trimBuffer;
}
//# sourceMappingURL=ReplaySubject.d.ts.map"""

    private val asyncSubjectDts = """
import { Subject } from './Subject';
/**
 * A variant of Subject that only emits a value when it completes. It will emit
 * its latest value to all its observers on completion.
 */
export declare class AsyncSubject<T> extends Subject<T> {
    private _value;
    private _hasValue;
    private _isComplete;
    next(value: T): void;
    complete(): void;
}
//# sourceMappingURL=AsyncSubject.d.ts.map"""

    private val notificationDts = """
import { PartialObserver, ObservableNotification, CompleteNotification, NextNotification, ErrorNotification } from './types';
import { Observable } from './Observable';
/**
 * @deprecated Use a string literal instead. `NotificationKind` will be replaced with a type alias in v8.
 * It will not be replaced with a const enum as those are not compatible with isolated modules.
 */
export declare enum NotificationKind {
    NEXT = "N",
    ERROR = "E",
    COMPLETE = "C"
}
/**
 * Represents a push-based event or value that an {@link Observable} can emit.
 * This class is particularly useful for operators that manage notifications,
 * like {@link materialize}, {@link dematerialize}, {@link observeOn}, and
 * others. Besides wrapping the actual delivered value, it also annotates it
 * with metadata of, for instance, what type of push message it is (`next`,
 * `error`, or `complete`).
 *
 * @see {@link materialize}
 * @see {@link dematerialize}
 * @see {@link observeOn}
 * @deprecated It is NOT recommended to create instances of `Notification` directly.
 * Rather, try to create POJOs matching the signature outlined in {@link ObservableNotification}.
 * For example: `{ kind: 'N', value: 1 }`, `{ kind: 'E', error: new Error('bad') }`, or `{ kind: 'C' }`.
 * Will be removed in v8.
 */
export declare class Notification<T> {
    readonly kind: 'N' | 'E' | 'C';
    readonly value?: T | undefined;
    readonly error?: any;
    /**
     * A value signifying that the notification will "next" if observed. In truth,
     * This is really synonymous with just checking `kind === "N"`.
     * @deprecated Will be removed in v8. Instead, just check to see if the value of `kind` is `"N"`.
     */
    readonly hasValue: boolean;
    /**
     * Creates a "Next" notification object.
     * @param kind Always `'N'`
     * @param value The value to notify with if observed.
     * @deprecated Internal implementation detail. Use {@link Notification#createNext createNext} instead.
     */
    constructor(kind: 'N', value?: T);
    /**
     * Creates an "Error" notification object.
     * @param kind Always `'E'`
     * @param value Always `undefined`
     * @param error The error to notify with if observed.
     * @deprecated Internal implementation detail. Use {@link Notification#createError createError} instead.
     */
    constructor(kind: 'E', value: undefined, error: any);
    /**
     * Creates a "completion" notification object.
     * @param kind Always `'C'`
     * @deprecated Internal implementation detail. Use {@link Notification#createComplete createComplete} instead.
     */
    constructor(kind: 'C');
    /**
     * Executes the appropriate handler on a passed `observer` given the `kind` of notification.
     * If the handler is missing it will do nothing. Even if the notification is an error, if
     * there is no error handler on the observer, an error will not be thrown, it will noop.
     * @param observer The observer to notify.
     */
    observe(observer: PartialObserver<T>): void;
    /**
     * Executes a notification on the appropriate handler from a list provided.
     * If a handler is missing for the kind of notification, nothing is called
     * and no error is thrown, it will be a noop.
     * @param next A next handler
     * @param error An error handler
     * @param complete A complete handler
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    do(next: (value: T) => void, error: (err: any) => void, complete: () => void): void;
    /**
     * Executes a notification on the appropriate handler from a list provided.
     * If a handler is missing for the kind of notification, nothing is called
     * and no error is thrown, it will be a noop.
     * @param next A next handler
     * @param error An error handler
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    do(next: (value: T) => void, error: (err: any) => void): void;
    /**
     * Executes the next handler if the Notification is of `kind` `"N"`. Otherwise
     * this will not error, and it will be a noop.
     * @param next The next handler
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    do(next: (value: T) => void): void;
    /**
     * Executes a notification on the appropriate handler from a list provided.
     * If a handler is missing for the kind of notification, nothing is called
     * and no error is thrown, it will be a noop.
     * @param next A next handler
     * @param error An error handler
     * @param complete A complete handler
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    accept(next: (value: T) => void, error: (err: any) => void, complete: () => void): void;
    /**
     * Executes a notification on the appropriate handler from a list provided.
     * If a handler is missing for the kind of notification, nothing is called
     * and no error is thrown, it will be a noop.
     * @param next A next handler
     * @param error An error handler
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    accept(next: (value: T) => void, error: (err: any) => void): void;
    /**
     * Executes the next handler if the Notification is of `kind` `"N"`. Otherwise
     * this will not error, and it will be a noop.
     * @param next The next handler
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    accept(next: (value: T) => void): void;
    /**
     * Executes the appropriate handler on a passed `observer` given the `kind` of notification.
     * If the handler is missing it will do nothing. Even if the notification is an error, if
     * there is no error handler on the observer, an error will not be thrown, it will noop.
     * @param observer The observer to notify.
     * @deprecated Replaced with {@link Notification#observe observe}. Will be removed in v8.
     */
    accept(observer: PartialObserver<T>): void;
    /**
     * Returns a simple Observable that just delivers the notification represented
     * by this Notification instance.
     *
     * @deprecated Will be removed in v8. To convert a `Notification` to an {@link Observable},
     * use {@link of} and {@link dematerialize}: `of(notification).pipe(dematerialize())`.
     */
    toObservable(): Observable<T>;
    private static completeNotification;
    /**
     * A shortcut to create a Notification instance of the type `next` from a
     * given value.
     * @param value The `next` value.
     * @return The "next" Notification representing the argument.
     * @deprecated It is NOT recommended to create instances of `Notification` directly.
     * Rather, try to create POJOs matching the signature outlined in {@link ObservableNotification}.
     * For example: `{ kind: 'N', value: 1 }`, `{ kind: 'E', error: new Error('bad') }`, or `{ kind: 'C' }`.
     * Will be removed in v8.
     */
    static createNext<T>(value: T): Notification<T> & NextNotification<T>;
    /**
     * A shortcut to create a Notification instance of the type `error` from a
     * given error.
     * @param err The `error` error.
     * @return The "error" Notification representing the argument.
     * @deprecated It is NOT recommended to create instances of `Notification` directly.
     * Rather, try to create POJOs matching the signature outlined in {@link ObservableNotification}.
     * For example: `{ kind: 'N', value: 1 }`, `{ kind: 'E', error: new Error('bad') }`, or `{ kind: 'C' }`.
     * Will be removed in v8.
     */
    static createError(err?: any): Notification<never> & ErrorNotification;
    /**
     * A shortcut to create a Notification instance of the type `complete`.
     * @return The valueless "complete" Notification.
     * @deprecated It is NOT recommended to create instances of `Notification` directly.
     * Rather, try to create POJOs matching the signature outlined in {@link ObservableNotification}.
     * For example: `{ kind: 'N', value: 1 }`, `{ kind: 'E', error: new Error('bad') }`, or `{ kind: 'C' }`.
     * Will be removed in v8.
     */
    static createComplete(): Notification<never> & CompleteNotification;
}
/**
 * Executes the appropriate handler on a passed `observer` given the `kind` of notification.
 * If the handler is missing it will do nothing. Even if the notification is an error, if
 * there is no error handler on the observer, an error will not be thrown, it will noop.
 * @param notification The notification object to observe.
 * @param observer The observer to notify.
 */
export declare function observeNotification<T>(notification: ObservableNotification<T>, observer: PartialObserver<T>): void;
//# sourceMappingURL=Notification.d.ts.map"""

    private val configDts = """
import { Subscriber } from './Subscriber';
import { ObservableNotification } from './types';
/**
 * The {@link GlobalConfig} object for RxJS. It is used to configure things
 * like how to react on unhandled errors.
 */
export declare const config: GlobalConfig;
/**
 * The global configuration object for RxJS, used to configure things
 * like how to react on unhandled errors. Accessible via {@link config}
 * object.
 */
export interface GlobalConfig {
    /**
     * A registration point for unhandled errors from RxJS. These are errors that
     * cannot were not handled by consuming code in the usual subscription path. For
     * example, if you have this configured, and you subscribe to an observable without
     * providing an error handler, errors from that subscription will end up here. This
     * will _always_ be called asynchronously on another job in the runtime. This is because
     * we do not want errors thrown in this user-configured handler to interfere with the
     * behavior of the library.
     */
    onUnhandledError: ((err: any) => void) | null;
    /**
     * A registration point for notifications that cannot be sent to subscribers because they
     * have completed, errored or have been explicitly unsubscribed. By default, next, complete
     * and error notifications sent to stopped subscribers are noops. However, sometimes callers
     * might want a different behavior. For example, with sources that attempt to report errors
     * to stopped subscribers, a caller can configure RxJS to throw an unhandled error instead.
     * This will _always_ be called asynchronously on another job in the runtime. This is because
     * we do not want errors thrown in this user-configured handler to interfere with the
     * behavior of the library.
     */
    onStoppedNotification: ((notification: ObservableNotification<any>, subscriber: Subscriber<any>) => void) | null;
    /**
     * The promise constructor used by default for {@link Observable#toPromise toPromise} and {@link Observable#forEach forEach}
     * methods.
     *
     * @deprecated As of version 8, RxJS will no longer support this sort of injection of a
     * Promise constructor. If you need a Promise implementation other than native promises,
     * please polyfill/patch Promise as you see appropriate. Will be removed in v8.
     */
    Promise?: PromiseConstructorLike;
    /**
     * If true, turns on synchronous error rethrowing, which is a deprecated behavior
     * in v6 and higher. This behavior enables bad patterns like wrapping a subscribe
     * call in a try/catch block. It also enables producer interference, a nasty bug
     * where a multicast can be broken for all observers by a downstream consumer with
     * an unhandled error. DO NOT USE THIS FLAG UNLESS IT'S NEEDED TO BUY TIME
     * FOR MIGRATION REASONS.
     *
     * @deprecated As of version 8, RxJS will no longer support synchronous throwing
     * of unhandled errors. All errors will be thrown on a separate call stack to prevent bad
     * behaviors described above. Will be removed in v8.
     */
    useDeprecatedSynchronousErrorHandling: boolean;
    /**
     * If true, enables an as-of-yet undocumented feature from v5: The ability to access
     * `unsubscribe()` via `this` context in `next` functions created in observers passed
     * to `subscribe`.
     *
     * This is being removed because the performance was severely problematic, and it could also cause
     * issues when types other than POJOs are passed to subscribe as subscribers, as they will likely have
     * their `this` context overwritten.
     *
     * @deprecated As of version 8, RxJS will no longer support altering the
     * context of next functions provided as part of an observer to Subscribe. Instead,
     * you will have access to a subscription or a signal or token that will allow you to do things like
     * unsubscribe and test closed status. Will be removed in v8.
     */
    useDeprecatedNextContext: boolean;
}
//# sourceMappingURL=config.d.ts.map"""

    private val firstValueFromDts = """
import { Observable } from './Observable';
export interface FirstValueFromConfig<T> {
    defaultValue: T;
}
export declare function firstValueFrom<T, D>(source: Observable<T>, config: FirstValueFromConfig<D>): Promise<T | D>;
export declare function firstValueFrom<T>(source: Observable<T>): Promise<T>;
//# sourceMappingURL=firstValueFrom.d.ts.map"""

    private val lastValueFromDts = """
import { Observable } from './Observable';
export interface LastValueFromConfig<T> {
    defaultValue: T;
}
export declare function lastValueFrom<T, D>(source: Observable<T>, config: LastValueFromConfig<D>): Promise<T | D>;
export declare function lastValueFrom<T>(source: Observable<T>): Promise<T>;
//# sourceMappingURL=lastValueFrom.d.ts.map"""

    private val actionDts = """
import { Scheduler } from '../Scheduler';
import { Subscription } from '../Subscription';
import { SchedulerAction } from '../types';
/**
 * A unit of work to be executed in a `scheduler`. An action is typically
 * created from within a {@link SchedulerLike} and an RxJS user does not need to concern
 * themselves about creating and manipulating an Action.
 *
 * ```ts
 * class Action<T> extends Subscription {
 *   new (scheduler: Scheduler, work: (state?: T) => void);
 *   schedule(state?: T, delay: number = 0): Subscription;
 * }
 * ```
 */
export declare class Action<T> extends Subscription {
    constructor(scheduler: Scheduler, work: (this: SchedulerAction<T>, state?: T) => void);
    /**
     * Schedules this action on its parent {@link SchedulerLike} for execution. May be passed
     * some context object, `state`. May happen at some point in the future,
     * according to the `delay` parameter, if specified.
     * @param state Some contextual data that the `work` function uses when called by the
     * Scheduler.
     * @param delay Time to wait before executing the work, where the time unit is implicit
     * and defined by the Scheduler.
     * @return A subscription in order to be able to unsubscribe the scheduled work.
     */
    schedule(state?: T, delay?: number): Subscription;
}
//# sourceMappingURL=Action.d.ts.map"""

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

    private val rxjsCore: List<SourceFileEntry> = listOf(
        SourceFileEntry("/rxjs/index.d.ts", rxjsIndexDts),
        SourceFileEntry("/rxjs/internal/types.d.ts", typesDts),
        SourceFileEntry("/rxjs/internal/Observable.d.ts", observableDts),
        SourceFileEntry("/rxjs/internal/Subject.d.ts", subjectDts),
        SourceFileEntry("/rxjs/internal/Subscription.d.ts", subscriptionDts),
        SourceFileEntry("/rxjs/internal/Subscriber.d.ts", subscriberDts),
        SourceFileEntry("/rxjs/internal/Operator.d.ts", operatorDts),
        SourceFileEntry("/rxjs/internal/Scheduler.d.ts", schedulerDts),
        SourceFileEntry("/rxjs/internal/BehaviorSubject.d.ts", behaviorSubjectDts),
        SourceFileEntry("/rxjs/internal/ReplaySubject.d.ts", replaySubjectDts),
        SourceFileEntry("/rxjs/internal/AsyncSubject.d.ts", asyncSubjectDts),
        SourceFileEntry("/rxjs/internal/Notification.d.ts", notificationDts),
        SourceFileEntry("/rxjs/internal/config.d.ts", configDts),
        SourceFileEntry("/rxjs/internal/firstValueFrom.d.ts", firstValueFromDts),
        SourceFileEntry("/rxjs/internal/lastValueFrom.d.ts", lastValueFromDts),
        SourceFileEntry("/rxjs/internal/scheduler/Action.d.ts", actionDts),
    )

    /** (EXT.16) Wired to the package: `rxjs`, entry `/rxjs/index.d.ts`. */
    internal fun generateRxjsCore(): KotlinExternals =
        generateKotlinExternals(rxjsCore, module = ModuleWiring("rxjs", "/rxjs/index.d.ts"))

    @Test
    fun `rxjs core generates and the generated kotlin compiles`() {
        val result = generateRxjsCore()
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        val errorCodes = result.errors.map { it.code }
        assert(compileErrors.isEmpty())
        assert(check.successful)
        // (EXT.16) The entry's two `/// <reference path="…/index.d.ts" />`
        // lines name the `operators` and `testing` entries, which this
        // fixture does not carry: TS6053 twice, and nothing else.
        assert(errorCodes == listOf(6053, 6053))
    }

    @Test
    fun `rxjs core's spine renders - the callable chain, the class hierarchy, the receiver and the value`() {
        val result = generateRxjsCore()
        val rendered = result.kotlin
        // The three callable interfaces are function-type aliases, each
        // naming the one below it — a Kotlin typealias may name a
        // parameterised typealias.
        val unary = "public typealias UnaryFunction<T, R> = (T) -> R\n" in rendered
        val operator = "public typealias OperatorFunction<T, R> = UnaryFunction<Observable<T>, Observable<R>>\n" in rendered
        val mono = "public typealias MonoTypeOperatorFunction<T> = OperatorFunction<T, T>\n" in rendered
        // `pipe` is typed by the alias, with its arguments.
        val pipe = "    public fun <A, B> pipe(op1: OperatorFunction<T, A>, op2: OperatorFunction<A, B>): Observable<B>\n" in rendered
        // A class extending a generated class from another file and
        // implementing a generated interface: the declared zero-parameter
        // constructor renders as `()`.
        val subject = "public open external class Subject<T>() : Observable<T>, SubscriptionLike {\n" in rendered
        val asyncSubject = "public open external class AsyncSubject<T>() : Subject<T> {\n" in rendered
        // The `this`-typed callback is a Kotlin RECEIVER, never a positional
        // parameter — the one overload whose `state` is not optional maps.
        // (EXT.17) The `this` parameter is a marker, not a receiver: Kotlin/JS refuses receivers in externals.
        val schedule = "    public fun <T> schedule(work: (T) -> Unit /* xtsc: this parameter SchedulerAction<T> not carried */, delay: Double, state: T): Subscription\n" in rendered
        val positional = "(SchedulerAction<T>, T) -> Unit" !in rendered
        val value = "public external val EMPTY_SUBSCRIPTION: Subscription\n" in rendered
        // (EXT.11b) The cheap mapping wins, read off the probe: an array of
        // a generated generic, `any` with no marker inside a function type,
        // a nullable union of a generated generic, a literal union widened,
        // the `vararg` rest parameter of `pipe`'s open-arity overload, and an
        // optional member typed by a nullable union rendering ONE `?`.
        val observers = "    public var observers: Array<Observer<T>>\n" in rendered
        val observerError = "public external interface Observer<T> {\n    public var next: (T) -> Unit\n    public var error: (Any?) -> Unit\n    public var complete: () -> Unit\n}\n" in rendered
        val source = "    public var source: Observable<Any?>?\n    public var operator: Operator<Any?, T>?\n" in rendered
        val kind = "    public val kind: String\n    public val value: T?\n    public val error: Any?\n" in rendered
        val subscribe = "    public fun subscribe(next: ((T) -> Unit)? = definedExternally, error: ((Any?) -> Unit)? = definedExternally, complete: (() -> Unit)? = definedExternally): Subscription\n" in rendered
        val restPipe = "op9: OperatorFunction<H, I>, vararg operations: OperatorFunction<Any?, Any?>): Observable<Any?>\n" in rendered
        val subscription = "public open external class Subscription(initialTeardown: (() -> Unit)? = definedExternally) : SubscriptionLike {\n" in rendered
        val anonymous = "public open external class AnonymousSubject<T>(destination: Observer<T>? = definedExternally, source: Observable<T>? = definedExternally) : Subject<T> {\n" in rendered
        // (EXT.16) Wired to the package: every declaration the entry
        // re-exports binds under its own name (rxjs renames nothing, so no
        // `@JsName` at all), the SEVEN value-bearing declarations the entry
        // does not reach are loud — `EMPTY_SUBSCRIPTION`, `EMPTY_OBSERVER`,
        // `Action`, `AnonymousSubject`, `SafeSubscriber`, `isSubscription`,
        // `observeNotification`, each an internal path — and the 156 entry
        // lines naming files outside the fixture are loud at the statement.
        val header = rendered.startsWith("@file:JsModule(\"rxjs\")\n\n")
        val internalValue = "/* xtsc: value EMPTY_SUBSCRIPTION is not exported by the package entry - an internal path a consumer cannot bind */\npublic external val EMPTY_SUBSCRIPTION: Subscription\n" in rendered
        val internalPaths = Regex("not exported by the package entry").findAll(rendered).count() == 7
        val noJsName = "@JsName" !in rendered
        val outsideFixture = Regex("resolves to no file in this generation").findAll(rendered).count() == 156
        val noLaterRung = "module wiring is a later rung" !in rendered
        assert(header)
        assert(internalValue)
        assert(internalPaths)
        assert(noJsName)
        assert(outsideFixture)
        assert(noLaterRung)
        assert(unary)
        assert(operator)
        assert(mono)
        assert(pipe)
        assert(subject)
        assert(asyncSubject)
        assert(schedule)
        assert(positional)
        assert(value)
        assert(observers)
        assert(observerError)
        assert(source)
        assert(kind)
        assert(subscribe)
        assert(restPipe)
        assert(subscription)
        assert(anonymous)
    }

    @Test
    fun `rxjs core's inexpressible shapes stay loud - never silent`() {
        val result = generateRxjsCore()
        val rendered = result.kotlin
        // The union-of-everything input alias and the literal-union falsy
        // alias refuse as declarations...
        val input = "/* xtsc: skipped generic type alias ObservableInput with unmappable body */" in rendered
        val falsy = "/* xtsc: skipped type alias Falsy with unmappable body" in rendered
        val teardown = "/* xtsc: skipped type alias TeardownLogic with unmappable body Subscription | Unsubscribable | (() => void) | void */" in rendered
        // ...`typeof Action` is marked by what was WRITTEN, not by the
        // instance type the lens answers for a class value (CHK.73)...
        val scheduler = "public open external class Scheduler(schedulerActionCtor: Any? /* xtsc: unmapped typeof Action */, now: (() -> Double)? = definedExternally) : SchedulerLike {\n" in rendered
        val bareAction = "schedulerActionCtor: Action" !in rendered
        // ...the symbol-keyed member and the interface-extends-class heritage
        // are the markers they always were.
        val symbolKeyed = "public external interface InteropObservable<T> {\n    /* xtsc: skipped member with a non-identifier name */\n}\n" in rendered
        val schedulerAction = "public external interface SchedulerAction<T> {\n    /* xtsc: skipped heritage clause extends Subscription */\n" in rendered
        // No call signature survives as a nameless method anywhere.
        val noNamelessFun = "fun ``(" !in rendered
        // (EXT.11b) What the cheap mapping deliberately does NOT reach: a
        // union of two DISTINCT texts (`Subscriber<any> | Observer<any>`),
        // an OPTIONAL parameter inside a function type (arity), and a
        // `Promise<T>` (no classpath in the gate, not a built-in).
        val distinctUnion = "public open external class Subscriber<T>(destination: Any? /* xtsc: unmapped Subscriber<any> | Observer<any> */ = definedExternally) : Subscription, Observer<T> {\n" in rendered
        val optionalInFunctionType = "public fun <T> create(next: Any? /* xtsc: unmapped (x?: T | undefined) => void */ = definedExternally, error: Any? /* xtsc: unmapped (e?: any | undefined) => void */ = definedExternally, complete: (() -> Unit)? = definedExternally): Subscriber<T>\n" in rendered
        val promise = "    public fun toPromise(): Any? /* xtsc: unmapped Promise<any> */\n" in rendered
        assert(distinctUnion)
        assert(optionalInFunctionType)
        assert(promise)
        assert(input)
        assert(falsy)
        assert(teardown)
        assert(scheduler)
        assert(bareAction)
        assert(symbolKeyed)
        assert(schedulerAction)
        assert(noNamelessFun)
    }

}
