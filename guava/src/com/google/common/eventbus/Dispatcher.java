/*
 * Copyright (C) 2014 The Guava Authors
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

package com.google.common.eventbus;

import com.google.common.collect.Queues;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Handler for dispatching events to subscribers, providing different event ordering guarantees that
 * make sense for different situations.
 *
 * <p><b>Note:</b> The dispatcher is orthogonal to the subscriber's {@code Executor}. The dispatcher
 * controls the order in which events are dispatched, while the executor controls how (i.e. on which
 * thread) the subscriber is actually called when an event is dispatched to it.
 *
 * @author Colin Decker
 */
abstract class Dispatcher {

  /**
   * Returns a dispatcher that queues events that are posted reentrantly on a thread that is already
   * dispatching an event, guaranteeing that all events posted on a single thread are dispatched to
   * all subscribers in the order they are posted.
   *
   * <p>When all subscribers are dispatched to using a <i>direct</i> executor (which dispatches on
   * the same thread that posts the event), this yields a breadth-first dispatch order on each      // 广度优先分派顺序
   * thread. That is, all subscribers to a single event A will be called before any subscribers to
   * any events B and C that are posted to the event bus by the subscribers to A.
   */
  static Dispatcher perThreadDispatchQueue() {
    return new PerThreadQueuedDispatcher();   // 内部是每个线程都有一个队列的
  }

  /**
   * Returns a dispatcher that queues events that are posted in a single global queue. This behavior
   * matches the original behavior of AsyncEventBus exactly, but is otherwise not especially useful.
   * For async dispatch, an {@linkplain #immediate() immediate} dispatcher should generally be
   * preferable.
   */
  static Dispatcher legacyAsync() {
    return new LegacyAsyncDispatcher();
  }

  /**
   * Returns a dispatcher that dispatches events to subscribers immediately as they're posted
   * without using an intermediate queue to change the dispatch order. This is effectively a
   * depth-first dispatch order, vs. breadth-first when using a queue.  // 深度优先顺序
   */
  static Dispatcher immediate() {
    return ImmediateDispatcher.INSTANCE;  // 立即分派,没有队列作缓冲的.
  }

  /**
   * Dispatches the given {@code event} to the given {@code subscribers}.
   */
  abstract void dispatch(Object event, Iterator<Subscriber> subscribers);

  /**
   * Implementation of a {@link #perThreadDispatchQueue()} dispatcher.
   */
  private static final class PerThreadQueuedDispatcher extends Dispatcher {

    // This dispatcher matches the original dispatch behavior of EventBus.

    /**
     * Per-thread queue of events to dispatch.
     */
    private final ThreadLocal<Queue<Event>> queue =
        new ThreadLocal<Queue<Event>>() {
          @Override
          protected Queue<Event> initialValue() {
            return Queues.newArrayDeque();        // 每个线程都有自己的线程本地队列
          }
        };

    /**
     * Per-thread dispatch state, used to avoid reentrant event dispatching.  // 避免重入的分发
     */
    private final ThreadLocal<Boolean> dispatching =    // 这里和当时我用来做去重的思路是一致的,都是利用一个ThreadLocal标记
        new ThreadLocal<Boolean>() {
          @Override
          protected Boolean initialValue() {
            return false;
          }
        };

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      checkNotNull(subscribers);
      Queue<Event> queueForThread = queue.get();            // 这是线程本地队列,所以是线程安全的.
      queueForThread.offer(new Event(event, subscribers));  // offer方法添加到队列中.

      if (!dispatching.get()) {   // 判断没有发送.
        dispatching.set(true);    // 设置在发送中,dispatching也是线程安全的. 这里的get和set会不会有线程安全问题?
        try {                     // 不会,因为都是线程本地变量,同一时间只会有一个线程访问. 这种控制就是防止重复通知的.
          Event nextEvent;
          while ((nextEvent = queueForThread.poll()) != null) {           // 每次从队列中取一个,直到没有可取的.
            while (nextEvent.subscribers.hasNext()) {                     // 遍历所有的监听者,逐个通知
              nextEvent.subscribers.next().dispatchEvent(nextEvent.event);  // 因为这里的dispatchEvent方法可能是异步的,所以上面先保存到一个队列中.
            }
          }
        } finally {
          dispatching.remove();   // 完成后删除标记
          queue.remove();         // 删除线程本地队列
        }
      }
    }

    private static final class Event {    // Event对象是对event属性的包装对象.
      private final Object event;
      private final Iterator<Subscriber> subscribers;

      private Event(Object event, Iterator<Subscriber> subscribers) {
        this.event = event;
        this.subscribers = subscribers;
      }
    }
  }

  /**
   * Implementation of a {@link #legacyAsync()} dispatcher.
   */
  private static final class LegacyAsyncDispatcher extends Dispatcher {

    // This dispatcher matches the original dispatch behavior of AsyncEventBus.
    //
    // We can't really make any guarantees about the overall dispatch order for this dispatcher in
    // a multithreaded environment for a couple reasons:
    //
    // 1. Subscribers to events posted on different threads can be interleaved with each other
    //    freely. (A event on one thread, B event on another could yield any of
    //    [a1, a2, a3, b1, b2], [a1, b2, a2, a3, b2], [a1, b2, b3, a2, a3], etc.)
    // 2. It's possible for subscribers to actually be dispatched to in a different order than they
    //    were added to the queue. It's easily possible for one thread to take the head of the
    //    queue, immediately followed by another thread taking the next element in the queue. That
    //    second thread can then dispatch to the subscriber it took before the first thread does.
    //
    // All this makes me really wonder if there's any value in queueing here at all. A dispatcher
    // that simply loops through the subscribers and dispatches the event to each would actually
    // probably provide a stronger order guarantee, though that order would obviously be different
    // in some cases.

    /**
     * Global event queue.
     */
    private final ConcurrentLinkedQueue<EventWithSubscriber> queue =
        Queues.newConcurrentLinkedQueue();

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      while (subscribers.hasNext()) {
        queue.add(new EventWithSubscriber(event, subscribers.next()));  // 这种先add进queue队列
      }

      EventWithSubscriber e;
      while ((e = queue.poll()) != null) {    // 在调用poll方法,从队列中取有什么意思?
        e.subscriber.dispatchEvent(e.event);
      }
    }

    private static final class EventWithSubscriber {
      private final Object event;
      private final Subscriber subscriber;

      private EventWithSubscriber(Object event, Subscriber subscriber) {
        this.event = event;
        this.subscriber = subscriber;
      }
    }
  }

  /**
   * Implementation of {@link #immediate()}.
   */
  private static final class ImmediateDispatcher extends Dispatcher {
    private static final ImmediateDispatcher INSTANCE = new ImmediateDispatcher();

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {   // 这个其实就是靠客户端线程, 同步的遍历调用监听者.
      checkNotNull(event);
      while (subscribers.hasNext()) {
        subscribers.next().dispatchEvent(event);  // 每个Dispatcher内部还是依赖executor执行注册者的监听方法
      }
    }
  }
}
