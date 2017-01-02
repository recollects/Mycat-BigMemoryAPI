package io.mycat.bigmem.cacheway.alloctor.directmove;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mycat.bigmem.buffer.DirectMemAddressInf;
import io.mycat.bigmem.buffer.MycatBufferBase;
import io.mycat.bigmem.buffer.MycatMovableBufer;

/**
 * 缓冲内存页的数据
* 源文件名：BufferPage.java
* 文件版本：1.0.0
* 创建作者：liujun
* 创建日期：2016年12月27日
* 修改作者：liujun
* 修改日期：2016年12月27日
* 文件描述：TODO
* 版权所有：Copyright 2016 zjhz, Inc. All Rights Reserved.
*/
public class DirectMoveBufferPage {

	/**
	 * 操作的buffer信息
	* @字段说明 buffer
	*/
	private MycatBufferBase buffer;

	/**
	* 每个chunk的大小
	* @字段说明 chunkSize
	*/
	private int chunkSize;

	/**
	* 总的chunk数
	* @字段说明 chunkIndex
	*/
	private int chunkCount;

	/**
	* 用于标识内存是否使用集合
	* @字段说明 memUseSet
	*/
	private final BitSet memUseSet;

	/**
	 * 进行分配的数组的初始化
	 */
	private final List<MycatBufferBase> allotBuffer;

	/**
	* 是否锁定标识
	* @字段说明 isLock
	*/
	private AtomicBoolean isLock = new AtomicBoolean(false);

	/**
	* 可以使用的chunkNum
	* @字段说明 useMemoryChunkNum
	*/
	private int canUseChunkNum;

	/**
	* 构造方法
	* @param memorySize
	* @param chunkSize
	*/
	public DirectMoveBufferPage(MycatBufferBase buffer, int chunkSize) {
		this.buffer = buffer;
		// 设置chunk的大小
		this.chunkSize = chunkSize;
		// 设置chunk的数量
		this.chunkCount = (int) buffer.limit() / this.chunkSize;
		// 设置当前内存标识块的大小
		this.memUseSet = new BitSet(this.chunkCount);
		// 默认可使用的chunk数量为总的chunk数
		this.canUseChunkNum = chunkCount;
		// 进行分配对象的初始化
		this.allotBuffer = new ArrayList<>(this.chunkCount);

	}

	/**
	* 检查当前内存页能否满足内存数据的分配要求
	* 方法描述
	* @param chunkNum
	* @return 1,可分配 0，不能
	* @创建日期 2016年12月19日
	*/
	public boolean checkNeedChunk(int chunkNum) {
		// 如果当前可分配的内存块满足要求
		if (this.canUseChunkNum >= chunkNum) {
			return true;
		}
		return false;
	}

	/**
	* 获得chunk的buffer信息
	* 方法描述
	* @param needChunkSize
	* @param timeout 系统过期时间
	* @return
	* @创建日期 2016年12月19日
	*/
	public MycatBufferBase alloactionMemory(int needChunkSize) {
		// 如果当前的可分配的内在块小于需要内存块，则返回
		if (canUseChunkNum < needChunkSize) {
			return null;
		}

		// 如果当前不能加锁成功，则返回为null
		if (!isLock.compareAndSet(false, true)) {
			return null;
		}

		try {
			int startIndex = -1;
			int endIndex = 0;
			// 找到当前连续未使用的内存块
			for (int i = 0; i < chunkCount; i++) {
				if (memUseSet.get(i) == false) {
					if (startIndex == -1) {
						startIndex = i;
						endIndex = 1;
						// 只需要1时，进不需再进行遍历
						if (needChunkSize == 1) {
							break;
						}
					} else {
						if (++endIndex == needChunkSize) {
							break;
						}
					}
				}
				// 如果已经使用，则标识当前已经使用，则从已经使用的位置开始
				else {
					startIndex = -1;
					endIndex = 0;
				}
			}

			// 如果找到适合的内存块大小
			if (endIndex == needChunkSize) {
				// 将这一批数据标识为已经使用
				int needChunkEnd = startIndex + needChunkSize;
				memUseSet.set(startIndex, needChunkEnd);

				MycatMovableBufer moveBuffer = null;

				// 检查当前对象是否实现了可移动接口
				if (buffer instanceof MycatMovableBufer) {
					moveBuffer = (MycatMovableBufer) buffer;

					// 标识为不可移动
					moveBuffer.beginOp();

					// 标识开始与结束号
					buffer.putPosition(startIndex * chunkSize);
					buffer.limit(needChunkEnd * chunkSize);

					// 进行数据进行匹配分段操作
					MycatBufferBase bufferResult = buffer.slice();

					// 当前可使用的，为之前的结果前去当前的需要的，
					canUseChunkNum = canUseChunkNum - needChunkSize;

					// 将当前分配的对象信息记录到集合中
					this.allotBuffer.add(bufferResult);

					// 标识当前操作完成
					moveBuffer.commitOp();

					return bufferResult;
				}

			} else {
				return null;
			}

		} finally {
			isLock.set(false);
		}

		return null;
	}

	/**
	* 进行内存的归还操作，以便后面再使用
	* 方法描述
	* @param parentBuffer 内存页信息
	* @param chunkStart 开始块的号
	* @param chunkNum 归还的数量
	* @创建日期 2016年12月19日
	*/
	public boolean recycleBuffer(MycatBufferBase bufferParam) {

		// 获得内存buffer
		DirectMemAddressInf thisNavBuf = (DirectMemAddressInf) bufferParam;
		// attachment对象在buf.slice();的时候将attachment对象设置为总的buff对象
		DirectMemAddressInf parentBuf = (DirectMemAddressInf) thisNavBuf.getAttach();

		if (this.buffer == parentBuf) {
			// 如果加锁失败，则执行其他代码
			if (!isLock.compareAndSet(false, true)) {
				Thread.yield();
			}

			try {
				// 计算chunk归还的数量
				int chunkNum = (int) (buffer.capacity() - buffer.limit()) / chunkSize;

				int chunkAdd = buffer.limit() % chunkSize == 0 ? (int) buffer.limit() / chunkSize
						: (int) buffer.limit() / chunkSize + 1;
				// 已经使用的地址减去父类最开始的地址，即为所有已经使用的地址，除以chunkSize得到chunk当前开始的地址,得到整块内存开始的地址
				int startChunk = (int) ((thisNavBuf.address() - parentBuf.address()) / chunkSize) + chunkAdd;

				int endChunkNum = startChunk + chunkNum;

				// 将当前指定的内存块归还
				memUseSet.clear(startChunk, endChunkNum);

				// 引用对象的容量进行重新标识
				bufferParam.limit((bufferParam.limit() - chunkNum) * chunkSize);
				bufferParam.capacity((bufferParam.limit() - chunkNum) * chunkSize);

				// 归还了内存，则需要将可使用的内存加上归还的内存
				this.canUseChunkNum = canUseChunkNum + chunkNum;

			} finally {
				isLock.set(false);
			}

			return true;

		}

		return false;
	}

}
