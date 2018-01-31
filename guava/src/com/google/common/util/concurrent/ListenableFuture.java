/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.util.concurrent;

import com.google.common.annotations.GwtCompatible;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * A {@link Future} that accepts completion listeners. Each listener has an associated executor, and
 * it is invoked using this executor once the future's computation is {@linkplain Future#isDone()
 * complete}. If the computation has already completed when the listener is added, the listener will
 * execute immediately.
 *
 * <p>See the Guava User Guide article on
 * <a href="https://github.com/google/guava/wiki/ListenableFutureExplained">
 * {@code ListenableFuture}</a>.
 *
 * <h3>Purpose</h3>
 *
 * <p>The main purpose of {@code ListenableFuture} is to help you chain together a graph of
 * asynchronous operations. You can chain them together manually with calls to methods like
 * {@link Futures#transform(ListenableFuture, com.google.common.base.Function, Executor)
 * Futures.transform}, but you will often find it easier to use a framework. Frameworks automate the
 * process, often adding features like monitoring, debugging, and cancellation. Examples of
 * frameworks include:
 *
 * <ul>
 * <li><a href="http://google.github.io/dagger/producers.html">Dagger Producers</a>
 * </ul>
 *
 * <p>The main purpose of {@link #addListener addListener} is to support this chaining. You will    // 可以支持链式调用
 * rarely use it directly, in part because it does not provide direct access to the {@code Future}  // 很少直接使用它,部分因为它没有提供直接访问Future结果
 * result. (If you want such access, you may prefer {@link Futures#addCallback                      // 你可以使用Futures.addCallback
 * Futures.addCallback}.) Still, direct {@code addListener} calls are occasionally useful:          // 或者直接用addListener添加监听,有时候比较有用
 *
 * <pre>   {@code
 *   final String name = ...;
 *   inFlight.add(name);
 *   ListenableFuture<Result> future = service.query(name);
 *   future.addListener(new Runnable() {
 *     public void run() {
 *       processedCount.incrementAndGet();
 *       inFlight.remove(name);
 *       lastProcessed.set(name);
 *       logger.info("Done with {0}", name);
 *     }
 *   }, executor);}</pre>
 *
 * <h3>How to get an instance</h3>
 *
 * <p>We encourage you to return {@code ListenableFuture} from your methods so that your users can    // 我们鼓励从方法返回ListenableFuture对象
 * take advantage of the {@linkplain Futures utilities built atop the class}. The way that you will   // 这样用户可以利用Futures工具类
 * create {@code ListenableFuture} instances depends on how you currently create {@code Future}       // 你创建ListenableFuture实例的方式,依赖于你有多频繁的创建Future实例
 * instances:
 * <ul>
 * <li>If you receive them from an {@code java.util.concurrent.ExecutorService}, convert that         // 如果你从ListenableFuture接收Future
 *     service to a {@link ListeningExecutorService}, usually by calling                              // 可以使用MoreExecutors#listeningDecorator,装饰ExecutorService
 *     {@link MoreExecutors#listeningDecorator(java.util.concurrent.ExecutorService)                  // 获取到的ListeningExecutorService,返回的就是ListenableFuture
 *     MoreExecutors.listeningDecorator}.
 * <li>If you manually call {@link java.util.concurrent.FutureTask#set} or a similar method, create   // 如果你手工调用FutureTask.set或者类似方法
 *     a {@link SettableFuture} instead. (If your needs are more complex, you may prefer              // 可以创建一个SettableFuture替代
 *     {@link AbstractFuture}.)                                                                       // 如果需要比较复杂, 可以对抽象AbstractFuture进行覆盖订制
 * </ul>
 *
 * <p><b>Test doubles</b>: If you need a {@code ListenableFuture} for your test, try a {@link         // SettableFuture或者Futures#immediateFuture
 * SettableFuture} or one of the methods in the {@link Futures#immediateFuture Futures.immediate*}
 * family. <b>Avoid</b> creating a mock or stub {@code Future}. Mock and stub implementations are
 * fragile because they assume that only certain methods will be called and because they often
 * implement subtleties of the API improperly.
 *
 * <p><b>Custom implementation</b>: Avoid implementing {@code ListenableFuture} from scratch. If you
 * can't get by with the standard implementations, prefer to derive a new {@code Future} instance
 * with the methods in {@link Futures} or, if necessary, to extend {@link AbstractFuture}.
 *
 * <p>Occasionally, an API will return a plain {@code Future} and it will be impossible to change
 * the return type. For this case, we provide a more expensive workaround in {@code                   // JdkFutureAdapters更重量
 * JdkFutureAdapters}. However, when possible, it is more efficient and reliable to create a {@code
 * ListenableFuture} directly.
 *
 * @author Sven Mawson
 * @author Nishant Thakkar
 * @since 1.0
 */
@GwtCompatible
public interface ListenableFuture<V> extends Future<V> {
  /**
   * Registers a listener to be {@linkplain Executor#execute(Runnable) run} on the given executor.
   * The listener will run when the {@code Future}'s computation is {@linkplain Future#isDone()
   * complete} or, if the computation is already complete, immediately.
   *
   * <p>There is no guaranteed ordering of execution of listeners, but any listener added through
   * this method is guaranteed to be called once the computation is complete.
   *
   * <p>Exceptions thrown by a listener will be propagated up to the executor. Any exception thrown
   * during {@code Executor.execute} (e.g., a {@code RejectedExecutionException} or an exception
   * thrown by {@linkplain MoreExecutors#directExecutor direct execution}) will be caught and
   * logged.
   *
   * <p>Note: For fast, lightweight listeners that would be safe to execute in any thread, consider   // 可以在任何线程中执行的轻量级的监听器,考虑使用MoreExecutors#directExecutor
   * {@link MoreExecutors#directExecutor}. Otherwise, avoid it. Heavyweight {@code directExecutor}    // 否则避免使用它. 重量级的directExecutor可能导致问题
   * listeners can cause problems, and these problems can be difficult to reproduce because they      // 问题很难重现,因为他们依赖时间. 重量级的
   * depend on timing. For example:
   *
   * <ul>
   * <li>The listener may be executed by the caller of {@code addListener}. That caller may be a UI   // 1.影响UI的响应
   * thread or other latency-sensitive thread. This can harm UI responsiveness.
   * <li>The listener may be executed by the thread that completes this {@code Future}. That thread   // 2.系统内部的线程,如果是RPC线程,被阻塞了
   * may be an internal system thread such as an RPC network thread. Blocking that thread may stall   // 会阻塞整个系统,甚至造成死锁
   * progress of the whole system. It may even cause a deadlock.
   * <li>The listener may delay other listeners, even listeners that are not themselves {@code        // 3.重量级的listener可能延迟其他listeners
   * directExecutor} listeners.
   * </ul>
   *
   * <p>This is the most general listener interface. For common operations performed using
   * listeners, see {@link Futures}. For a simplified but general listener interface, see {@link
   * Futures#addCallback addCallback()}.
   *
   * <p>Memory consistency effects: Actions in a thread prior to adding a listener <a
   * href="https://docs.oracle.com/javase/specs/jls/se7/html/jls-17.html#jls-17.4.5">
   * <i>happen-before</i></a> its execution begins, perhaps in another thread.
   *
   * @param listener the listener to run when the computation is complete
   * @param executor the executor to run the listener in
   * @throws RejectedExecutionException if we tried to execute the listener immediately but the
   *     executor rejected it.
   */
  void addListener(Runnable listener, Executor executor);
}
