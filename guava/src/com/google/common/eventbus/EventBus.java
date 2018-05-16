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

package com.google.common.eventbus;

import com.google.common.annotations.Beta;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.MoreExecutors;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Dispatches events to listeners, and provides ways for listeners to register themselves.
 *
 * <p>The EventBus allows publish-subscribe-style communication between components without requiring  // EventBus允许多个组件之间发布注册模式的通讯
 * the components to explicitly register with one another (and thus be aware of each other). It is    // 不需要组件显示的注册到另一个组件上, (彼此知道双方的存在)
 * designed exclusively to replace traditional Java in-process event distribution using explicit      // 他被设计专门用于替换 传统的 java 执行中时间分派, 使用显示的注册
 * registration. It is <em>not</em> a general-purpose publish-subscribe system, nor is it intended    // 它不是一个发布注册系统, 也不打断用于内部的通讯
 * for interprocess communication.
 *
 * <h2>Receiving Events</h2>
 *
 * <p>To receive events, an object should:
 * <ol>
 * <li>Expose a public method, known as the <i>event subscriber</i>, which accepts a single argument  // 1. 必须只有一个入参
 *     of the type of event desired;
 * <li>Mark it with a {@link Subscribe} annotation;                                                   // 2. 打上Subscribe注解, 这个注解只能在方法上.
 * <li>Pass itself to an EventBus instance's {@link #register(Object)} method.                        // 3. 使用EventBus实例的register方法, 将事件接收对象注册进去
 * </ol>
 *
 * <h2>Posting Events</h2>
 *
 * <p>To post an event, simply provide the event object to the {@link #post(Object)} method. The      // 发送事件, 只要简单的调用post方法
 * EventBus instance will determine the type of event and route it to all registered listeners.       // EventBus实例会确定事件类型, 路由到所有的注册监听器
 *
 * <p>Events are routed based on their type &mdash; an event will be delivered to any subscriber for
 * any type to which the event is <em>assignable.</em> This includes implemented interfaces, all      // 包括实现了接口,所有的子类,和所有由超类实现的接口
 * superclasses, and all interfaces implemented by superclasses.
 *
 * <p>When {@code post} is called, all registered subscribers for an event are run in sequence, so    // post方法调用了,所有注册的订阅者,按顺序执行
 * subscribers should be reasonably quick. If an event may trigger an extended process (such as a     // 所以注册者必须相对要快点. 如果一个时间可能触发扩展的进程,比如查询数据库
 * database load), spawn a thread or queue it for later. (For a convenient way to do this, use an     // 请另起一个线程或者加入到队列中稍后执行.
 * {@link AsyncEventBus}.)                                                                            // 更方便的方式是使用异步事件总线: AsyncEventBus.
 *
 * <h2>Subscriber Methods</h2>
 *
 * <p>Event subscriber methods must accept only one argument: the event.                              // 每个方法必须只有一个入参: 事件对象.
 *
 * <p>Subscribers should not, in general, throw. If they do, the EventBus will catch and log the      // 订阅者一般不应该抛出异常. 如果抛出了,EventBus会catch异常,打印异常
 * exception. This is rarely the right solution for error handling and should not be relied upon; it  // 这一般不是正确的处理方法, 用于处理报错, 不应该立即响应.
 * is intended solely to help find problems during development.                                       // 它仅仅打算用于帮助在开发的过程中发现问题
 *
 * <p>The EventBus guarantees that it will not call a subscriber method from multiple threads         // 还是不翻译中文了,不然还是太依赖中文,要多理解注释的意思.英文的注释有时候更准确!
 * simultaneously, unless the method explicitly allows it by bearing the
 * {@link AllowConcurrentEvents} annotation. If this annotation is not present, subscriber methods
 * need not worry about being reentrant, unless also called from outside the EventBus.
 *
 * <h2>Dead Events</h2>
 *
 * <p>If an event is posted, but no registered subscribers can accept it, it is considered "dead."
 * To give the system a second chance to handle dead events, they are wrapped in an instance of
 * {@link DeadEvent} and reposted.
 *
 * <p>If a subscriber for a supertype of all events (such as Object) is registered, no event will
 * ever be considered dead, and no DeadEvents will be generated. Accordingly, while DeadEvent
 * extends {@link Object}, a subscriber registered to receive any Object will never receive a
 * DeadEvent.
 *
 * <p>This class is safe for concurrent use.  // 这个类是并发安全的!
 *
 * <p>See the Guava User Guide article on
 * <a href="https://github.com/google/guava/wiki/EventBusExplained">{@code EventBus}</a>.
 *
 * @author Cliff Biffle
 * @since 10.0
 */
@Beta
public class EventBus {

  private static final Logger logger = Logger.getLogger(EventBus.class.getName());

  private final String identifier;
  private final Executor executor;
  private final SubscriberExceptionHandler exceptionHandler;

  private final SubscriberRegistry subscribers = new SubscriberRegistry(this);  // SubscriberRegistry包装了this对象.
  private final Dispatcher dispatcher;

  /**
   * Creates a new EventBus named "default".
   */
  public EventBus() {
    this("default");
  }

  /**
   * Creates a new EventBus with the given {@code identifier}.
   *
   * @param identifier a brief name for this bus, for logging purposes. Should be a valid Java
   *     identifier.
   */
  public EventBus(String identifier) {
    this(
        identifier,
        MoreExecutors.directExecutor(),
        Dispatcher.perThreadDispatchQueue(),
        LoggingHandler.INSTANCE);
  }

  /**
   * Creates a new EventBus with the given {@link SubscriberExceptionHandler}.
   *
   * @param exceptionHandler Handler for subscriber exceptions.
   * @since 16.0
   */
  public EventBus(SubscriberExceptionHandler exceptionHandler) {  // 就像线程池的Executors一样,不同的构造函数, 可以创建各种用途的EventBus.
    this(
        "default",
        MoreExecutors.directExecutor(),
        Dispatcher.perThreadDispatchQueue(),
        exceptionHandler);
  }

  EventBus(   // 其他所有的构造函数, 都是调用这个包级私有的构造函数, 这种写法蛮好.
      String identifier,
      Executor executor,
      Dispatcher dispatcher,
      SubscriberExceptionHandler exceptionHandler) {
    this.identifier = checkNotNull(identifier);
    this.executor = checkNotNull(executor);
    this.dispatcher = checkNotNull(dispatcher);
    this.exceptionHandler = checkNotNull(exceptionHandler); // 上面如果不指定,都会产生一个默认的,所以都可以checkNotNull.
  }

  /**
   * Returns the identifier for this event bus.
   *
   * @since 19.0
   */
  public final String identifier() {
    return identifier;
  }

  /**
   * Returns the default executor this event bus uses for dispatching events to subscribers.
   */
  final Executor executor() {   // 都是申明一个final方法,而不是直接把对象暴露出去,那样不安全.
    return executor;
  }

  /**
   * Handles the given exception thrown by a subscriber with the given context.
   */
  void handleSubscriberException(Throwable e, SubscriberExceptionContext context) {
    checkNotNull(e);
    checkNotNull(context);
    try {
      exceptionHandler.handleException(e, context);
    } catch (Throwable e2) {
      // if the handler threw an exception... well, just log it
      logger.log(
          Level.SEVERE,
          String.format(Locale.ROOT, "Exception %s thrown while handling exception: %s", e2, e),
          e2);
    }
  }

  /**
   * Registers all subscriber methods on {@code object} to receive events.
   *
   * @param object object whose subscriber methods should be registered.
   */
  public void register(Object object) {
    subscribers.register(object);
  }

  /**
   * Unregisters all subscriber methods on a registered {@code object}.
   *
   * @param object object whose subscriber methods should be unregistered.
   * @throws IllegalArgumentException if the object was not previously registered.
   */
  public void unregister(Object object) {
    subscribers.unregister(object);
  }

  /**
   * Posts an event to all registered subscribers. This method will return successfully after the
   * event has been posted to all subscribers, and regardless of any exceptions thrown by
   * subscribers.
   *
   * <p>If no subscribers have been subscribed for {@code event}'s class, and {@code event} is not
   * already a {@link DeadEvent}, it will be wrapped in a DeadEvent and reposted.
   *
   * @param event event to post.
   */
  public void post(Object event) {
    Iterator<Subscriber> eventSubscribers = subscribers.getSubscribers(event);
    if (eventSubscribers.hasNext()) { // dispatcher有多种策略,
      dispatcher.dispatch(event, eventSubscribers);
    } else if (!(event instanceof DeadEvent)) {       // 没有,就直接发送一个DeadEvent, DeadEvent是event的包装对象.
      // the event had no subscribers and was not itself a DeadEvent
      post(new DeadEvent(this, event));         // 这里是递归调用, 下次调用方法, 会再次判断有没有监听DeadEvent的
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).addValue(identifier).toString();
  }

  /**
   * Simple logging handler for subscriber exceptions.
   */
  static final class LoggingHandler implements SubscriberExceptionHandler {   // 对每个模块都抽象出接口来
    static final LoggingHandler INSTANCE = new LoggingHandler();

    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext context) {
      Logger logger = logger(context);
      if (logger.isLoggable(Level.SEVERE)) {  // 这个是JDK的Level
        logger.log(Level.SEVERE, message(context), exception);
      }
    }

    private static Logger logger(SubscriberExceptionContext context) {
      return Logger.getLogger(EventBus.class.getName() + "." + context.getEventBus().identifier());   // 日志的工厂模式,为什么要用工厂? 可以方便替换内部实现
    }

    private static String message(SubscriberExceptionContext context) {   // logger方法 和 这个message方法, 都可以直接在handleException里写的
      Method method = context.getSubscriberMethod();                      // 但是其实是独立的方法, 拆成私有方法, 会更清晰些.
      return "Exception thrown by subscriber method "
          + method.getName()
          + '('
          + method.getParameterTypes()[0].getName()
          + ')'
          + " on subscriber "
          + context.getSubscriber()
          + " when dispatching event: "
          + context.getEvent();
    }
  }
}
