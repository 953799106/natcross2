package person.pluto.natcross2.nio;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import person.pluto.natcross2.executor.NatcrossExecutor;
import person.pluto.natcross2.utils.Assert;
import person.pluto.natcross2.utils.CountWaitLatch;

/**
 * <p>
 * nio 容器
 * </p>
 *
 * @author Pluto
 * @since 2021-04-13 09:25:51
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NioHallows implements Runnable {

	public static final NioHallows INSTANCE = new NioHallows();

	/**
	 * 注册监听动作
	 * <p>
	 * 要注意这里只拿最后的一次注册为准，即 {@code channel} 只能与一个 {@code proccesser} 动作对应
	 *
	 * @param channel
	 * @param ops        依据以下值进行或运算进行最后结果设定，并且 {@code channel} 要支持相应的动作
	 *                   <p>
	 *                   - {@link SelectionKey#OP_ACCEPT}
	 *                   <p>
	 *                   - {@link SelectionKey#OP_CONNECT}
	 *                   <p>
	 *                   - {@link SelectionKey#OP_READ}
	 *                   <p>
	 *                   - {@link SelectionKey#OP_WRITE}
	 * @param proccesser 要执行的动作
	 * @throws IOException
	 * @author Pluto
	 * @since 2021-04-26 15:55:38
	 */
	public static void register(SelectableChannel channel, int ops, INioProcesser proccesser) throws IOException {
		INSTANCE.register0(channel, ops, proccesser);
	}

	/**
	 * 根据 {@link SelectionKey} 恢复监听事件的注册
	 *
	 * @param key 原始的key
	 * @param ops 要与通过 {@link #register(SelectableChannel, int, INioProcesser)}
	 *            注册的事件统一
	 * @throws IOException
	 * @author Pluto
	 * @since 2021-05-07 13:21:49
	 */
	public static boolean reRegisterByKey(SelectionKey key, int ops) {
		return INSTANCE.reRegisterByKey0(key, ops);
	}

	/**
	 * 释放注册
	 *
	 * @param channel
	 * @author Pluto
	 * @since 2021-04-26 16:03:51
	 */
	public static void release(SelectableChannel channel) {
		INSTANCE.release0(channel);
	}

	private volatile Thread myThread = null;

	private volatile boolean alive = false;

	private volatile Selector selector;
	private final Object selectorLock = new Object();

	private final CountWaitLatch countWaitLatch = new CountWaitLatch();

	private final Map<SelectableChannel, ProcesserHolder> channelProcesserMap = new ConcurrentHashMap<>();

	@Setter
	@Getter
	private long selectTimeout = 10L;
	@Setter
	@Getter
	private long wakeupSleepNanos = 1000000L;

	/**
	 * 获取 {@link #selector}
	 * <p>
	 * 若 {@link #selector} 未有值，则会进行初始化：打开selector，并执行 {@link #start()}
	 *
	 * @return
	 * @throws IOException
	 * @author Pluto
	 * @since 2021-04-26 16:04:30
	 */
	public Selector getSelector() throws IOException {
		if (Objects.isNull(this.selector)) {
			synchronized (this.selectorLock) {
				// 二次校验
				if (Objects.isNull(this.selector)) {
					this.selector = Selector.open();
					this.start();
				}
			}
		}

		return this.selector;
	}

	/**
	 * 获取唤醒后的 {@link #selector}
	 * <p>
	 * 注意，若 {@link #run()} 快于你的任务，还是会被再次阻塞，只是执行了一次 {@link Selector#wakeup()}
	 *
	 * @return
	 * @throws IOException
	 * @author Pluto
	 * @since 2021-04-26 16:07:00
	 */
	public Selector getWakeupSelector() throws IOException {
		return this.getSelector().wakeup();
	}

	/**
	 * 注册监听动作
	 * <p>
	 * 要注意这里只拿最后的一次注册为准，即 {@code channel} 只能与一个 {@code proccesser} 动作对应
	 *
	 * @param channel
	 * @param ops        依据以下值进行或运算进行最后结果设定，并且 {@code channel} 要支持相应的动作
	 *                   <p>
	 *                   - {@link SelectionKey#OP_ACCEPT}
	 *                   <p>
	 *                   - {@link SelectionKey#OP_CONNECT}
	 *                   <p>
	 *                   - {@link SelectionKey#OP_READ}
	 *                   <p>
	 *                   - {@link SelectionKey#OP_WRITE}
	 * @param proccesser 要执行的动作
	 * @throws IOException
	 * @author Pluto
	 * @since 2021-04-26 15:55:38
	 */
	public void register0(SelectableChannel channel, int ops, INioProcesser proccesser) throws IOException {
		Objects.requireNonNull(channel, "channel non null");
		try {
			this.channelProcesserMap.put(channel, ProcesserHolder.of(channel, ops, proccesser));
			channel.configureBlocking(false);

			this.countWaitLatch.countUp();
			// 这里有个坑点，如果在select中，这里会被阻塞
			channel.register(this.getWakeupSelector(), ops);
		} catch (Throwable e) {
			this.channelProcesserMap.remove(channel);
			throw e;
		} finally {
			this.countWaitLatch.countDown();
		}
	}

	/**
	 * 根据 {@link SelectionKey} 恢复监听事件的注册
	 *
	 * @param key 原始的key
	 * @param ops 要与通过 {@link #register0(SelectableChannel, int, INioProcesser)}
	 *            注册的事件统一
	 * @throws IOException
	 * @author Pluto
	 * @since 2021-05-07 13:21:49
	 */
	public boolean reRegisterByKey0(SelectionKey key, int ops) {
		Objects.requireNonNull(key, "key non null");

		Assert.state(key.selector() == this.selector, "this SelectionKey is not belong NioHallows's selector");

		if (!key.isValid()) {
			return false;
		}

		// 通过事件和源码分析，恢复注册是通过updateKeys.addLast进行，虽然没有被阻塞，但是需要进行一次唤醒才可以成功恢复事件监听
		// 因无法获知是否成功注入selector，所以必须要进行一次唤醒操作，并且没有阻塞的问题，所以这里不通过countWaitLatch进行同步
		key.interestOps(ops);

		try {
			this.getWakeupSelector();
		} catch (IOException e) {
			// 出错了交给其他的流程逻辑，这里只进行一次唤醒
		}

		return true;
	}

	/**
	 * 释放注册
	 *
	 * @param channel
	 * @author Pluto
	 * @since 2021-04-26 16:03:51
	 */
	public void release0(SelectableChannel channel) {
		if (Objects.isNull(channel)) {
			return;
		}
		this.channelProcesserMap.remove(channel);

		SelectionKey key = channel.keyFor(this.selector);

		if (Objects.nonNull(key)) {
			key.cancel();
		}
	}

	@Override
	public void run() {
		CountWaitLatch countWaitLatch = this.countWaitLatch;
		Map<SelectableChannel, ProcesserHolder> chanelProcesserMap = this.channelProcesserMap;

		for (; this.alive;) {
			try {
				// 给注册事务一个时间，如果等待时间太长（可能需要注入的太多），就跳出再去获取新事件，防止饿死
				try {
					countWaitLatch.await(this.getWakeupSleepNanos(), TimeUnit.NANOSECONDS);
				} catch (InterruptedException e) {
					log.warn("selector wait register timeout");
				}

				// 采用有期限的监听，以免线程太快，没有来的及注册，就永远阻塞在那里了
				int select = getSelector().select(this.getSelectTimeout());
				if (select <= 0) {
					continue;
				}

				Iterator<SelectionKey> iterator = getSelector().selectedKeys().iterator();
				for (; iterator.hasNext();) {
					SelectionKey key = iterator.next();
					iterator.remove();
					key.interestOps(0);

					ProcesserHolder processerHolder = chanelProcesserMap.get(key.channel());
					if (Objects.isNull(processerHolder)) {
						key.cancel();
						continue;
					}

					NatcrossExecutor.executeNioAction(() -> {
						processerHolder.proccess(key);
					});
				}

			} catch (IOException e) {
				log.error("NioHallows run exception", e);
			}
		}
	}

	/**
	 * 启动nio事件监听
	 *
	 * @author Pluto
	 * @since 2021-04-26 16:33:07
	 */
	public void start() {
		this.alive = true;
		if (this.myThread == null || !this.myThread.isAlive()) {
			this.myThread = new Thread(this);
			this.myThread.setName("nio-hallows");
			this.myThread.start();

			log.info("NioHallows is started!");
		}
	}

	/**
	 * 退出nio事件监听
	 *
	 * @author Pluto
	 * @since 2021-04-26 16:33:33
	 */
	public void cancel() {
		log.info("NioHallows cancell");

		this.alive = false;

		Selector selector;
		if ((selector = this.selector) != null) {
			this.selector = null;
			try {
				selector.close();
			} catch (IOException e) {
			}
		}

		Thread myThread;
		if ((myThread = this.myThread) != null) {
			this.myThread = null;
			myThread.interrupt();
		}
	}

}
