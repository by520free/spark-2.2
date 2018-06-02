/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}

import org.apache.spark.{ExecutorAllocationClient, SparkConf, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config
import org.apache.spark.util.{Clock, SystemClock, Utils}

/**
 * BlacklistTracker is designed to track problematic executors and nodes.  It supports blacklisting
 * executors and nodes across an entire application (with a periodic expiry).  TaskSetManagers add
 * additional blacklisting of executors and nodes for individual tasks and stages which works in
 * concert with the blacklisting here.
  *
  * BlacklistTracker用于跟踪有问题的执行器和节点。它支持在整个应用程序中对执行器和节点进行黑名单(具有周期性失效)。
  * tasksetmanager为单独的任务和阶段添加了额外的执行器和节点黑名单，这些任务和阶段与这里的黑名单一起工作。
 *
 * The tracker needs to deal with a variety of workloads, eg.: 跟踪器需要处理各种工作负载，例如:
 *
 *  * bad user code --  this may lead to many task failures, but that should not count against
 *      individual executors
 *  * many small stages -- this may prevent a bad executor for having many failures within one
 *      stage, but still many failures over the entire application
 *  * "flaky" executors -- they don't fail every task, but are still faulty enough to merit
 *      blacklisting
  *
  *  糟糕的用户代码——这可能导致许多任务失败，但这不应该对单个执行器起作用。
  *  许多小阶段(stages)——这可能会阻止一个糟糕的执行程序在一个阶段中出现多次失败，但在整个应用程序中仍然有许多失败。
  *  “片状”的执行者(executors)——他们不会每项任务都失败，但仍然有足够的缺陷，值得列入黑名单。
 *
 * See the design doc on SPARK-8425 for a more in-depth discussion. 请参阅SPARK-8425的设计文档，以进行更深入的讨论。
 *
 * THREADING: As with most helpers of TaskSchedulerImpl, this is not thread-safe.  Though it is
 * called by multiple threads, callers must already have a lock on the TaskSchedulerImpl.  The
 * one exception is [[nodeBlacklist()]], which can be called without holding a lock.
  *
  * 线程:就像大多数任务调度程序(TaskSchedulerImpl)的助手一样，这不是线程安全的。虽然它是由多个线程调用的，但是调用者必须已经拥有了任务调度程序
  * (TaskSchedulerImpl)的锁。唯一的例外是nodeBlacklist()，它可以在不持有锁的情况下被调用。
  *
  * BlacklistTracker：的意思是黑名单追踪
 */
private[scheduler] class BlacklistTracker (
    private val listenerBus: LiveListenerBus,
    conf: SparkConf,
    allocationClient: Option[ExecutorAllocationClient],
    clock: Clock = new SystemClock()) extends Logging {
    // SystemClock()是一个获取当前时间，和sleep一段时间的包装器


  def this(sc: SparkContext, allocationClient: Option[ExecutorAllocationClient]) = {
    this(sc.listenerBus, sc.conf, allocationClient)
  }

  // 验证黑名单配置是否一致;如果没有，抛出一个异常。只有在启用黑名单时才调用。
  BlacklistTracker.validateBlacklistConfs(conf)
  private val MAX_FAILURES_PER_EXEC = conf.get(config.MAX_FAILURES_PER_EXEC)
  private val MAX_FAILED_EXEC_PER_NODE = conf.get(config.MAX_FAILED_EXEC_PER_NODE)
  // 得到黑名单超时时间
  val BLACKLIST_TIMEOUT_MILLIS = BlacklistTracker.getBlacklistTimeout(conf)

  /**
   * A map from executorId to information on task failures.  Tracks the time of each task failure,
   * so that we can avoid blacklisting executors due to failures that are very far apart.  We do not
   * actively remove from this as soon as tasks hit their timeouts, to avoid the time it would take
   * to do so.  But it will not grow too large, because as soon as an executor gets too many
   * failures, we blacklist the executor and remove its entry here.
   */
  private val executorIdToFailureList = new HashMap[String, ExecutorFailureList]()
  val executorIdToBlacklistStatus = new HashMap[String, BlacklistedExecutor]()
  val nodeIdToBlacklistExpiryTime = new HashMap[String, Long]()
  /**
   * An immutable copy of the set of nodes that are currently blacklisted.  Kept in an
   * AtomicReference to make [[nodeBlacklist()]] thread-safe.
   */
  private val _nodeBlacklist = new AtomicReference[Set[String]](Set())
  /**
   * Time when the next blacklist will expire.  Used as a
   * shortcut to avoid iterating over all entries in the blacklist when none will have expired.
   */
  var nextExpiryTime: Long = Long.MaxValue
  /**
   * Mapping from nodes to all of the executors that have been blacklisted on that node. We do *not*
   * remove from this when executors are removed from spark, so we can track when we get multiple
   * successive blacklisted executors on one node.  Nonetheless, it will not grow too large because
   * there cannot be many blacklisted executors on one node, before we stop requesting more
   * executors on that node, and we clean up the list of blacklisted executors once an executor has
   * been blacklisted for BLACKLIST_TIMEOUT_MILLIS.
   */
  val nodeToBlacklistedExecs = new HashMap[String, HashSet[String]]()

  /**
   * Un-blacklists executors and nodes that have been blacklisted for at least
   * BLACKLIST_TIMEOUT_MILLIS
   */
  def applyBlacklistTimeout(): Unit = {
    val now = clock.getTimeMillis()
    // quickly check if we've got anything to expire from blacklist -- if not, avoid doing any work
    if (now > nextExpiryTime) {
      // Apply the timeout to blacklisted nodes and executors
      val execsToUnblacklist = executorIdToBlacklistStatus.filter(_._2.expiryTime < now).keys
      if (execsToUnblacklist.nonEmpty) {
        // Un-blacklist any executors that have been blacklisted longer than the blacklist timeout.
        logInfo(s"Removing executors $execsToUnblacklist from blacklist because the blacklist " +
          s"for those executors has timed out")
        execsToUnblacklist.foreach { exec =>
          val status = executorIdToBlacklistStatus.remove(exec).get
          val failedExecsOnNode = nodeToBlacklistedExecs(status.node)
          listenerBus.post(SparkListenerExecutorUnblacklisted(now, exec))
          failedExecsOnNode.remove(exec)
          if (failedExecsOnNode.isEmpty) {
            nodeToBlacklistedExecs.remove(status.node)
          }
        }
      }
      val nodesToUnblacklist = nodeIdToBlacklistExpiryTime.filter(_._2 < now).keys
      if (nodesToUnblacklist.nonEmpty) {
        // Un-blacklist any nodes that have been blacklisted longer than the blacklist timeout.
        logInfo(s"Removing nodes $nodesToUnblacklist from blacklist because the blacklist " +
          s"has timed out")
        nodesToUnblacklist.foreach { node =>
          nodeIdToBlacklistExpiryTime.remove(node)
          listenerBus.post(SparkListenerNodeUnblacklisted(now, node))
        }
        _nodeBlacklist.set(nodeIdToBlacklistExpiryTime.keySet.toSet)
      }
      updateNextExpiryTime()
    }
  }

  private def updateNextExpiryTime(): Unit = {
    val execMinExpiry = if (executorIdToBlacklistStatus.nonEmpty) {
      executorIdToBlacklistStatus.map{_._2.expiryTime}.min
    } else {
      Long.MaxValue
    }
    val nodeMinExpiry = if (nodeIdToBlacklistExpiryTime.nonEmpty) {
      nodeIdToBlacklistExpiryTime.values.min
    } else {
      Long.MaxValue
    }
    nextExpiryTime = math.min(execMinExpiry, nodeMinExpiry)
  }


  def updateBlacklistForSuccessfulTaskSet(
      stageId: Int,
      stageAttemptId: Int,
      failuresByExec: HashMap[String, ExecutorFailuresInTaskSet]): Unit = {
    // if any tasks failed, we count them towards the overall failure count for the executor at
    // this point.
    val now = clock.getTimeMillis()
    failuresByExec.foreach { case (exec, failuresInTaskSet) =>
      val appFailuresOnExecutor =
        executorIdToFailureList.getOrElseUpdate(exec, new ExecutorFailureList)
      appFailuresOnExecutor.addFailures(stageId, stageAttemptId, failuresInTaskSet)
      appFailuresOnExecutor.dropFailuresWithTimeoutBefore(now)
      val newTotal = appFailuresOnExecutor.numUniqueTaskFailures

      val expiryTimeForNewBlacklists = now + BLACKLIST_TIMEOUT_MILLIS
      // If this pushes the total number of failures over the threshold, blacklist the executor.
      // If its already blacklisted, we avoid "re-blacklisting" (which can happen if there were
      // other tasks already running in another taskset when it got blacklisted), because it makes
      // some of the logic around expiry times a little more confusing.  But it also wouldn't be a
      // problem to re-blacklist, with a later expiry time.
      if (newTotal >= MAX_FAILURES_PER_EXEC && !executorIdToBlacklistStatus.contains(exec)) {
        logInfo(s"Blacklisting executor id: $exec because it has $newTotal" +
          s" task failures in successful task sets")
        val node = failuresInTaskSet.node
        executorIdToBlacklistStatus.put(exec, BlacklistedExecutor(node, expiryTimeForNewBlacklists))
        listenerBus.post(SparkListenerExecutorBlacklisted(now, exec, newTotal))
        executorIdToFailureList.remove(exec)
        updateNextExpiryTime()
        if (conf.get(config.BLACKLIST_KILL_ENABLED)) {
          allocationClient match {
            case Some(allocationClient) =>
              logInfo(s"Killing blacklisted executor id $exec " +
                s"since spark.blacklist.killBlacklistedExecutors is set.")
              allocationClient.killExecutors(Seq(exec), true, true)
            case None =>
              logWarning(s"Not attempting to kill blacklisted executor id $exec " +
                s"since allocation client is not defined.")
          }
        }

        // In addition to blacklisting the executor, we also update the data for failures on the
        // node, and potentially put the entire node into a blacklist as well.
        val blacklistedExecsOnNode = nodeToBlacklistedExecs.getOrElseUpdate(node, HashSet[String]())
        blacklistedExecsOnNode += exec
        // If the node is already in the blacklist, we avoid adding it again with a later expiry
        // time.
        if (blacklistedExecsOnNode.size >= MAX_FAILED_EXEC_PER_NODE &&
            !nodeIdToBlacklistExpiryTime.contains(node)) {
          logInfo(s"Blacklisting node $node because it has ${blacklistedExecsOnNode.size} " +
            s"executors blacklisted: ${blacklistedExecsOnNode}")
          nodeIdToBlacklistExpiryTime.put(node, expiryTimeForNewBlacklists)
          listenerBus.post(SparkListenerNodeBlacklisted(now, node, blacklistedExecsOnNode.size))
          _nodeBlacklist.set(nodeIdToBlacklistExpiryTime.keySet.toSet)
          if (conf.get(config.BLACKLIST_KILL_ENABLED)) {
            allocationClient match {
              case Some(allocationClient) =>
                logInfo(s"Killing all executors on blacklisted host $node " +
                  s"since spark.blacklist.killBlacklistedExecutors is set.")
                if (allocationClient.killExecutorsOnHost(node) == false) {
                  logError(s"Killing executors on node $node failed.")
                }
              case None =>
                logWarning(s"Not attempting to kill executors on blacklisted host $node " +
                  s"since allocation client is not defined.")
            }
          }
        }
      }
    }
  }

  def isExecutorBlacklisted(executorId: String): Boolean = {
    executorIdToBlacklistStatus.contains(executorId)
  }

  /**
   * Get the full set of nodes that are blacklisted.  Unlike other methods in this class, this *IS*
   * thread-safe -- no lock required on a taskScheduler.
   */
  def nodeBlacklist(): Set[String] = {
    _nodeBlacklist.get()
  }

  def isNodeBlacklisted(node: String): Boolean = {
    nodeIdToBlacklistExpiryTime.contains(node)
  }

  def handleRemovedExecutor(executorId: String): Unit = {
    // We intentionally do not clean up executors that are already blacklisted in
    // nodeToBlacklistedExecs, so that if another executor on the same node gets blacklisted, we can
    // blacklist the entire node.  We also can't clean up executorIdToBlacklistStatus, so we can
    // eventually remove the executor after the timeout.  Despite not clearing those structures
    // here, we don't expect they will grow too big since you won't get too many executors on one
    // node, and the timeout will clear it up periodically in any case.
    executorIdToFailureList -= executorId
  }


  /**
   * Tracks all failures for one executor (that have not passed the timeout).
   *
   * In general we actually expect this to be extremely small, since it won't contain more than the
   * maximum number of task failures before an executor is failed (default 2).
   */
  private[scheduler] final class ExecutorFailureList extends Logging {

    private case class TaskId(stage: Int, stageAttempt: Int, taskIndex: Int)

    /**
     * All failures on this executor in successful task sets.
     */
    private var failuresAndExpiryTimes = ArrayBuffer[(TaskId, Long)]()
    /**
     * As an optimization, we track the min expiry time over all entries in failuresAndExpiryTimes
     * so its quick to tell if there are any failures with expiry before the current time.
     */
    private var minExpiryTime = Long.MaxValue

    def addFailures(
        stage: Int,
        stageAttempt: Int,
        failuresInTaskSet: ExecutorFailuresInTaskSet): Unit = {
      failuresInTaskSet.taskToFailureCountAndFailureTime.foreach {
        case (taskIdx, (_, failureTime)) =>
          val expiryTime = failureTime + BLACKLIST_TIMEOUT_MILLIS
          failuresAndExpiryTimes += ((TaskId(stage, stageAttempt, taskIdx), expiryTime))
          if (expiryTime < minExpiryTime) {
            minExpiryTime = expiryTime
          }
      }
    }

    /**
     * The number of unique tasks that failed on this executor.  Only counts failures within the
     * timeout, and in successful tasksets.
     */
    def numUniqueTaskFailures: Int = failuresAndExpiryTimes.size

    def isEmpty: Boolean = failuresAndExpiryTimes.isEmpty

    /**
     * Apply the timeout to individual tasks.  This is to prevent one-off failures that are very
     * spread out in time (and likely have nothing to do with problems on the executor) from
     * triggering blacklisting.  However, note that we do *not* remove executors and nodes from
     * the blacklist as we expire individual task failures -- each have their own timeout.  Eg.,
     * suppose:
     *  * timeout = 10, maxFailuresPerExec = 2
     *  * Task 1 fails on exec 1 at time 0
     *  * Task 2 fails on exec 1 at time 5
     * -->  exec 1 is blacklisted from time 5 - 15.
     * This is to simplify the implementation, as well as keep the behavior easier to understand
     * for the end user.
     */
    def dropFailuresWithTimeoutBefore(dropBefore: Long): Unit = {
      if (minExpiryTime < dropBefore) {
        var newMinExpiry = Long.MaxValue
        val newFailures = new ArrayBuffer[(TaskId, Long)]
        failuresAndExpiryTimes.foreach { case (task, expiryTime) =>
          if (expiryTime >= dropBefore) {
            newFailures += ((task, expiryTime))
            if (expiryTime < newMinExpiry) {
              newMinExpiry = expiryTime
            }
          }
        }
        failuresAndExpiryTimes = newFailures
        minExpiryTime = newMinExpiry
      }
    }

    override def toString(): String = {
      s"failures = $failuresAndExpiryTimes"
    }
  }

}

private[scheduler] object BlacklistTracker extends Logging {

  private val DEFAULT_TIMEOUT = "1h"

  /**
   * Returns true if the blacklist is enabled, based on checking the configuration in the following
   * order:
   * 1. Is it specifically enabled or disabled?
   * 2. Is it enabled via the legacy timeout conf?
   * 3. Default is off
   */
  def isBlacklistEnabled(conf: SparkConf): Boolean = {
    conf.get(config.BLACKLIST_ENABLED) match {
      case Some(enabled) =>
        enabled
      case None =>
        // if they've got a non-zero setting for the legacy conf, always enable the blacklist,
        // otherwise, use the default.
        val legacyKey = config.BLACKLIST_LEGACY_TIMEOUT_CONF.key
        conf.get(config.BLACKLIST_LEGACY_TIMEOUT_CONF).exists { legacyTimeout =>
          if (legacyTimeout == 0) {
            logWarning(s"Turning off blacklisting due to legacy configuration: $legacyKey == 0")
            false
          } else {
            logWarning(s"Turning on blacklisting due to legacy configuration: $legacyKey > 0")
            true
          }
        }
    }
  }

  // 得到黑名单超时时间
  def getBlacklistTimeout(conf: SparkConf): Long = {
    conf.get(config.BLACKLIST_TIMEOUT_CONF).getOrElse {
      conf.get(config.BLACKLIST_LEGACY_TIMEOUT_CONF).getOrElse {
        Utils.timeStringAsMs(DEFAULT_TIMEOUT)
      }
    }
  }

  /**
   * Verify that blacklist configurations are consistent; if not, throw an exception.  Should only
   * be called if blacklisting is enabled.
   *
   * The configuration for the blacklist is expected to adhere to a few invariants.  Default
   * values follow these rules of course, but users may unwittingly change one configuration
   * without making the corresponding adjustment elsewhere.  This ensures we fail-fast when
   * there are such misconfigurations.
    *
    * 验证黑名单配置是否一致;如果没有，抛出一个异常。只有在启用黑名单时才调用。
    *
    * 黑名单的配置将遵循一些不变量。默认值遵循这些规则，但是用户可能在无意中更改了一个配置，而没有在其他地方进行相应的调整。
    * 这确保我们在出现这种错误配置时快速失败。
   */
  def validateBlacklistConfs(conf: SparkConf): Unit = {

    def mustBePos(k: String, v: String): Unit = {
      throw new IllegalArgumentException(s"$k was $v, but must be > 0.")
    }

    Seq(
      config.MAX_TASK_ATTEMPTS_PER_EXECUTOR,    // 每个executor最大的task尝试次数
      config.MAX_TASK_ATTEMPTS_PER_NODE,        // 每个node节点最大的task尝试次数
      config.MAX_FAILURES_PER_EXEC_STAGE,       // 每个executor最大的task失败次数
      config.MAX_FAILED_EXEC_PER_NODE_STAGE,    // 每个executor最大的stage失败次数
      config.MAX_FAILURES_PER_EXEC,
      config.MAX_FAILED_EXEC_PER_NODE
    ).foreach { config =>
      val v = conf.get(config)
      if (v <= 0) {
        // 如果value值小于0 说明没有config
        mustBePos(config.key, v.toString)
      }
    }

    // 得到黑名单超时时间
    val timeout = getBlacklistTimeout(conf)
    if (timeout <= 0) {
      // first, figure out where the timeout came from, to include the right conf in the message.
      conf.get(config.BLACKLIST_TIMEOUT_CONF) match {
        case Some(t) =>
          mustBePos(config.BLACKLIST_TIMEOUT_CONF.key, timeout.toString)
        case None =>
          mustBePos(config.BLACKLIST_LEGACY_TIMEOUT_CONF.key, timeout.toString)
      }
    }

    val maxTaskFailures = conf.get(config.MAX_TASK_FAILURES)
    val maxNodeAttempts = conf.get(config.MAX_TASK_ATTEMPTS_PER_NODE)

    if (maxNodeAttempts >= maxTaskFailures) {
      throw new IllegalArgumentException(s"${config.MAX_TASK_ATTEMPTS_PER_NODE.key} " +
        s"( = ${maxNodeAttempts}) was >= ${config.MAX_TASK_FAILURES.key} " +
        s"( = ${maxTaskFailures} ).  Though blacklisting is enabled, with this configuration, " +
        s"Spark will not be robust to one bad node.  Decrease " +
        s"${config.MAX_TASK_ATTEMPTS_PER_NODE.key}, increase ${config.MAX_TASK_FAILURES.key}, " +
        s"or disable blacklisting with ${config.BLACKLIST_ENABLED.key}")
    }
  }
}

private final case class BlacklistedExecutor(node: String, expiryTime: Long)