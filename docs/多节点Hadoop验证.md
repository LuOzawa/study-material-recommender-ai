# 3 节点 HDFS/YARN 验证记录

> 结论先行：在 **同一台 Windows 宿主上的 3 台 Ubuntu VM** 里跑通了真实 HDFS/YARN：
> 2 个 DataNode、2 个 NodeManager、`dfs.replication=2`、4 个 reducer，MapReduce 作业成功，
> 两个 worker 都实际跑了容器。它不是多物理机集群，不能用于硬件扩展性结论。

## 拓扑

| 主机 | IP | 内存 | 角色 |
| --- | --- | ---: | --- |
| `hadoop-master` | 192.168.57.128 | 2 GB | NameNode、SecondaryNameNode、ResourceManager |
| `hadoop-worker1` | 192.168.57.131 | 2 GB | DataNode、NodeManager |
| `hadoop-worker2` | 192.168.57.132 | 2 GB | DataNode、NodeManager |

- 宿主：Windows 10/11，16 逻辑核，15.7 GB RAM；三台 VM 共 6 GB。
- VMware Workstation NAT（网关 `192.168.57.2`），Ubuntu 24.04，Hadoop 3.3.6，JDK 17。
- YARN：每 worker `yarn.nodemanager.resource.memory-mb=1536`，
  AM 1024 MB、map/reduce 512 MB，`yarn.nodemanager.vmem-check-enabled=false`。
- HDFS：`dfs.replication=2`，NameNode 数据 `/opt/hadoop-data/namenode`，
  DataNode 数据 `/opt/hadoop-data/datanode`。

## 关键配置

三台机器 `/opt/hadoop-3.3.6/etc/hadoop/` 使用同一份：

```xml
<!-- core-site.xml -->
<property><name>fs.defaultFS</name><value>hdfs://hadoop-master:9000</value></property>
<property><name>hadoop.tmp.dir</name><value>/opt/hadoop-data/tmp</value></property>

<!-- hdfs-site.xml -->
<property><name>dfs.replication</name><value>2</value></property>
<property><name>dfs.namenode.name.dir</name><value>file:///opt/hadoop-data/namenode</value></property>
<property><name>dfs.datanode.data.dir</name><value>file:///opt/hadoop-data/datanode</value></property>

<!-- yarn-site.xml -->
<property><name>yarn.resourcemanager.hostname</name><value>hadoop-master</value></property>
<property><name>yarn.nodemanager.aux-services</name><value>mapreduce_shuffle</value></property>
<property><name>yarn.nodemanager.resource.memory-mb</name><value>1536</value></property>
<property><name>yarn.scheduler.maximum-allocation-mb</name><value>1536</value></property>

<!-- mapred-site.xml -->
<property><name>mapreduce.framework.name</name><value>yarn</value></property>
<property><name>yarn.app.mapreduce.am.resource.mb</name><value>1024</value></property>
<property><name>mapreduce.map.memory.mb</name><value>512</value></property>
<property><name>mapreduce.reduce.memory.mb</name><value>512</value></property>
<property><name>mapreduce.job.reduces</name><value>4</value></property>
```

`workers` 只写两个 worker；master 不跑 DataNode/NodeManager。

JDK 17 必需的容器参数（否则 Guice 在 AM 里报 `InaccessibleObjectException`）：

```xml
<property>
  <name>yarn.app.mapreduce.am.env</name>
  <value>HADOOP_MAPRED_HOME=/opt/hadoop-3.3.6,JAVA_TOOL_OPTIONS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED</value>
</property>
<property>
  <name>mapreduce.map.env</name>
  <value>HADOOP_MAPRED_HOME=/opt/hadoop-3.3.6,JAVA_TOOL_OPTIONS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED</value>
</property>
<property>
  <name>mapreduce.reduce.env</name>
  <value>HADOOP_MAPRED_HOME=/opt/hadoop-3.3.6,JAVA_TOOL_OPTIONS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED</value>
</property>
```

另外显式写死 Linux 容器 classpath，避免从 Windows 客户端提交时混入 `%PWD%`：

```xml
<property>
  <name>mapreduce.application.classpath</name>
  <value>/opt/hadoop-3.3.6/etc/hadoop:/opt/hadoop-3.3.6/share/hadoop/common/*:/opt/hadoop-3.3.6/share/hadoop/common/lib/*:/opt/hadoop-3.3.6/share/hadoop/hdfs/*:/opt/hadoop-3.3.6/share/hadoop/hdfs/lib/*:/opt/hadoop-3.3.6/share/hadoop/yarn/*:/opt/hadoop-3.3.6/share/hadoop/yarn/lib/*:/opt/hadoop-3.3.6/share/hadoop/mapreduce/*:/opt/hadoop-3.3.6/share/hadoop/mapreduce/lib/*</value>
</property>
```

## 复现步骤

1. 以已有单机 VM 为母盘，`vmrun -T ws clone ... full` 出两个 worker；每个 VMX 设置
   `memsize=2048`、`numvcpus=2`，删除 `ethernet0.generatedAddress` 让 VMware 重新生成 MAC。
2. 启动三台，设置 hostname、静态 IP、`/etc/hosts`：
   ```
   192.168.57.128 hadoop-master
   192.168.57.131 hadoop-worker1
   192.168.57.132 hadoop-worker2
   ```
   三台重新生成 SSH host key；master 对 worker 免密（`ssh hadoop-workerN hostname`）。
3. 把上面的配置复制到三台，清空旧单机数据：
   ```bash
   stop-yarn.sh || true
   stop-dfs.sh || true
   sudo rm -rf /opt/hadoop-data/{namenode,datanode,tmp,nm-local}
   sudo mkdir -p /opt/hadoop-data/{namenode,datanode,tmp,nm-local}
   sudo chown -R ubuntu:ubuntu /opt/hadoop-data
   ```
4. 只在 master 格式化，然后启动：
   ```bash
   hdfs namenode -format -force
   start-dfs.sh
   start-yarn.sh
   ```
5. 上传 CSV 并验证副本：
   ```bash
   hdfs dfs -mkdir -p /recommend/bench
   hdfs dfs -put -f /home/ubuntu/user_behaviors.csv /recommend/bench/user_behaviors.csv
   hdfs dfs -setrep -w 2 /recommend/bench/user_behaviors.csv
   hdfs fsck /recommend/bench/user_behaviors.csv -files -blocks -locations
   ```
6. 提交作业（普通 MapReduce jar，不是 Spring Boot 可执行 jar）：
   ```bash
   hadoop jar /home/ubuntu/hadoop-job.jar \
     /recommend/bench/user_behaviors.csv /recommend/bench/hotness-$(date +%s) 10 4
   ```

## 实测证据（2026-11-02）

`yarn node -list`：

```
Total Nodes:2
hadoop-worker2:41443  RUNNING  hadoop-worker2:8042  0
hadoop-worker1:38643  RUNNING  hadoop-worker1:8042  0
```

`hdfs dfsadmin -report`：`Live datanodes (2)`，容量共 56.04 GB。

`fsck`：

```
/recommend/bench/user_behaviors.csv 78040 bytes, replicated: replication=2, 1 block(s): OK
Live_repl=2 [192.168.57.132:9866, 192.168.57.131:9866]
Status: HEALTHY
Number of data-nodes: 2
```

作业 `application_1793612626950_0004`：

```
State: FINISHED
Final-State: SUCCEEDED
Progress: 100%
AM Host: hadoop-worker1
Aggregate Resource Allocation: 42153 MB-seconds, 53 vcore-seconds
```

输出：

```
Found 5 items
_SUCCESS
part-r-00000 ... part-r-00003
```

本次功能验证用的是 95 行 CSV，耗时约 26.0 秒，输出 `11 265.0, 8 262.0, 7 260.5 ...`。
5.3 的基准测试改用 2,963 行 CSV，耗时 26.4 秒，TopN 为 `26 225.0, 3 222.5, 11 222.5 ...`，
与 PostgreSQL 一致。两个 worker 的 `userlogs/application_1793612626950_0004` 都存在容器目录：

- worker1：`container_..._0004_01_000001/2/3`
- worker2：`container_..._0004_01_000004/5/6`

这证明容器确实分布到了两个 NodeManager，而不是只在一台机器上跑。

## 限制

- **同一宿主上的 3 个 VM**：有 2 个真实 DataNode/NodeManager，但共享 CPU、内存、磁盘和 NAT；
  不能拿它做多机架、多物理机或硬件线性扩展结论。
- 2 GB/worker 是很小的容量；本记录只证明功能链路与副本/容器分布，不代表生产吞吐。
- 从 Windows 宿主的 Spring 提交 `benchmark-cluster` 时，容器启动脚本会带 Windows `%PWD%` 格式，
  AM 报 `fg: no job control`；因此 5.3 的 MapReduce 数字来自集群内 `hadoop jar` CLI。
  该接口需在 Linux 宿主重新验证。
