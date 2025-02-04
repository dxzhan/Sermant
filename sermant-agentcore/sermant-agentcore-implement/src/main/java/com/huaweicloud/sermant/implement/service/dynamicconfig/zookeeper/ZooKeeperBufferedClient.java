/*
 * Copyright (C) 2021-2021 Huawei Technologies Co., Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.sermant.implement.service.dynamicconfig.zookeeper;

import com.huaweicloud.sermant.core.common.CommonConstant;
import com.huaweicloud.sermant.core.common.LoggerFactory;
import com.huaweicloud.sermant.core.config.ConfigManager;
import com.huaweicloud.sermant.core.notification.NotificationInfo;
import com.huaweicloud.sermant.core.notification.NotificationManager;
import com.huaweicloud.sermant.core.notification.ZookeeperNotificationType;
import com.huaweicloud.sermant.core.service.dynamicconfig.config.DynamicConfig;
import com.huaweicloud.sermant.core.utils.AesUtil;

import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@link ZooKeeper}的包装，封装原生api，提供更易用的api
 *
 * @author HapThorin
 * @version 1.0.0
 * @since 2021-12-15
 */
public class ZooKeeperBufferedClient implements Closeable {
    /**
     * zk路径分隔符
     */
    public static final char ZK_PATH_SEPARATOR = '/';

    /**
     * zk授权分隔符
     */
    public static final char ZK_AUTH_SEPARATOR = ':';

    /**
     * 动态配置信息
     */
    private static final DynamicConfig CONFIG = ConfigManager.getConfig(DynamicConfig.class);

    /**
     * 日志
     */
    private static final Logger LOGGER = LoggerFactory.getLogger();

    private static final String SCHEME = "digest";

    /**
     * zk客户端对象
     */
    private ZooKeeper zkClient;

    /**
     * 新建ZooKeeperBufferedClient，初始化zk客户端，并提供过期重连机制
     *
     * @param connectString 连接字符串，必须形如：{@code host:port[(,host:port)...]}
     * @param sessionTimeout 会话超时时间
     * @throws ZooKeeperInitException 依赖动态配置情况下，zookeeper初始化失败，需要中断Sermant
     */
    public ZooKeeperBufferedClient(String connectString, int sessionTimeout) {
        zkClient = newZkClient(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                // 连接过期重连
                if (event.getState() == Event.KeeperState.Expired) {
                    zkClient = newZkClient(connectString, sessionTimeout, this);
                }
                postZookeeperConnectNotification(event);
            }
        });
        checkConnect();
    }

    /**
     * 新建ZooKeeperBufferedClient，初始化zk客户端，并提供过期重连机制
     *
     * @param connectString 链接地址
     * @param sessionTimeout 超市时间
     * @param userName 用户名
     * @param password 用户密码
     * @param key 用户密钥
     */
    public ZooKeeperBufferedClient(String connectString, int sessionTimeout, String userName,
                                   String password, String key) {
        String authInfo = userName + ZK_AUTH_SEPARATOR + AesUtil.decrypt(key, password).orElse(null);
        zkClient = newZkClient(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                // 连接过期重连
                if (event.getState() == Event.KeeperState.Expired) {
                    zkClient = newZkClient(connectString, sessionTimeout, this);
                    waitConnect();
                    zkClient.addAuthInfo(SCHEME, authInfo.getBytes(CommonConstant.DEFAULT_CHARSET));
                }
                postZookeeperConnectNotification(event);
            }
        });
        checkConnect();
        zkClient.addAuthInfo(SCHEME, authInfo.getBytes(CommonConstant.DEFAULT_CHARSET));
    }

    /**
     * 发送zookeeper链接通知
     *
     * @param event zookeeper事件信息
     */
    private static void postZookeeperConnectNotification(WatchedEvent event) {
        if (NotificationManager.isEnable()) {
            if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                NotificationManager.doNotify(new NotificationInfo(ZookeeperNotificationType.CONNECTED, null));
            } else if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                NotificationManager.doNotify(new NotificationInfo(ZookeeperNotificationType.DISCONNECTED, null));
            }
        }
    }

    /**
     * 检验链接情况
     *
     * @throws ZooKeeperInitException zk初始化异常
     */
    private void checkConnect() {
        waitConnect();
        if (zkClient.getState() != ZooKeeper.States.CONNECTED
                && zkClient.getState() != ZooKeeper.States.CONNECTEDREADONLY) {
            throw new ZooKeeperInitException("Unable to connect to the zookeeper server.");
        }
    }

    /**
     * 链接等待
     */
    private void waitConnect() {
        int tryNum = 0;

        // 阻塞zookeeper连接过程，防止连接状态中导致依赖该服务的插件服务初始化失败
        while (zkClient.getState() == ZooKeeper.States.CONNECTING && tryNum++ <= CONFIG.getConnectRetryTimes()) {
            try {
                Thread.sleep(CONFIG.getConnectTimeout());
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }

    /**
     * 创建zk客户端
     *
     * @param connectString 连接字符串，必须形如：{@code host:port[(,host:port)...]}
     * @param sessionTimeout 会话超时时间
     * @param watcher 默认观察器
     * @return zk客户端
     * @throws ZooKeeperInitException zk初始化异常
     */
    private ZooKeeper newZkClient(String connectString, int sessionTimeout, Watcher watcher) {
        try {
            return new ZooKeeper(connectString, sessionTimeout, watcher);
        } catch (IOException ignored) {
            throw new ZooKeeperInitException("Connect to " + connectString + "failed. ");
        }
    }

    /**
     * 获取zk客户端，若客户端断开，则抛出异常
     *
     * @return zk客户端
     * @throws ZooKeeperConnectionException zk连接异常
     */
    private ZooKeeper getZkClient() {
        final ZooKeeper.States state = zkClient.getState();
        if (state == ZooKeeper.States.CONNECTED || state == ZooKeeper.States.CONNECTEDREADONLY) {
            return zkClient;
        }
        throw new ZooKeeperConnectionException("Unable to connect to the zookeeper server, connection timeout.");
    }

    /**
     * 判断节点是否存在
     *
     * @param path 节点路径
     * @return 节点是否存在
     */
    public boolean ifNodeExist(String path) {
        try {
            return zkClient.exists(path, false) != null;
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
    }

    /**
     * 查询节点内容
     *
     * @param path 节点路径
     * @return 节点内容
     */
    public String getNode(String path) {
        if (!ifNodeExist(path)) {
            return "";
        }
        final byte[] data;
        try {
            data = getZkClient().getData(path, false, null);
        } catch (KeeperException | InterruptedException ignored) {
            return "";
        }
        if (data == null) {
            return "";
        }
        return new String(data, CommonConstant.DEFAULT_CHARSET);
    }

    /**
     * 创建节点的前置节点
     *
     * @param path 路径
     * @return 是否全部创建成功
     */
    public boolean createParent(String path) {
        final int separatorIndex = path.lastIndexOf(ZK_PATH_SEPARATOR);
        if (separatorIndex == 0) {
            return true;
        }
        final String parent = path.substring(0, separatorIndex);
        if (ifNodeExist(parent)) {
            return true;
        }
        if (!createParent(parent)) {
            return false;
        }
        try {
            getZkClient().create(parent, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
        return true;
    }

    /**
     * 更新节点内容，不存在时自动创建
     *
     * @param path 节点路径
     * @param data 数据信息
     * @return 是否更新成功
     */
    public boolean updateNode(String path, String data) {
        try {
            if (ifNodeExist(path)) {
                getZkClient().setData(path, data.getBytes(CommonConstant.DEFAULT_CHARSET), -1);
            } else {
                if (!createParent(path)) {
                    return false;
                }
                getZkClient().create(path, data.getBytes(CommonConstant.DEFAULT_CHARSET), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
        return true;
    }

    /**
     * 移除节点
     *
     * @param path 节点路径
     * @return 是否成功移除节点
     */
    public boolean removeNode(String path) {
        if (!ifNodeExist(path)) {
            return true;
        }
        try {
            getZkClient().delete(path, -1);
        } catch (InterruptedException | KeeperException ignored) {
            return false;
        }
        return true;
    }

    /**
     * 查询节点下所有子孙节点的路径集合
     *
     * @param path 节点
     * @return 子孙节点路径集合
     */
    public List<String> listAllNodes(String path) {
        if (!ifNodeExist(path)) {
            return Collections.emptyList();
        }
        final List<String> children;
        try {
            children = getZkClient().getChildren(path, false);
        } catch (KeeperException | InterruptedException ignored) {
            return Collections.emptyList();
        }
        final List<String> nodes = new ArrayList<>();
        for (String child : children) {
            final String childPath = path + ZK_PATH_SEPARATOR + child;
            nodes.add(childPath);
            nodes.addAll(listAllNodes(childPath));
        }
        return nodes;
    }

    /**
     * 添加循环的临时数据监听器，该监听器将在触发后重新注册，直到接收到移除监听器事件
     * <p>
     * 注意，当同一节点的其他监听器被精准移除时，由于该监听器无法鉴别到底是不是移除自身，因此会选择放弃循环注册
     *
     * @param path 节点路径
     * @param watcher 实际执行的监听器
     * @param handler 监听器循环注册失败后的异常处理器
     * @return 是否成功添加循环的临时数据监听器
     */
    public boolean addDataLoopWatch(String path, Watcher watcher, BreakHandler handler) {
        final Watcher bufferedWatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                final Event.EventType type = event.getType();
                if (type == Event.EventType.DataWatchRemoved) {
                    return;
                }
                watcher.process(event);
                try {
                    getZkClient().exists(path, this);
                } catch (KeeperException | InterruptedException e) {
                    handler.handle(e);
                }
            }
        };
        try {
            getZkClient().exists(path, bufferedWatcher);
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
        return true;
    }

    /**
     * 添加持久递归的监听器，对子孙节点有效
     *
     * @param path 节点路径
     * @param watcher 监听器
     * @return 是否成功添加持久递归的监听器
     */
    public boolean addPersistentRecursiveWatches(String path, Watcher watcher) {
        try {
            getZkClient().addWatch(path, watcher, AddWatchMode.PERSISTENT_RECURSIVE);
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
        return true;
    }

    /**
     * 移除数据监听器
     *
     * @param path 节点路径
     * @return 是否成功移除数据监听器
     */
    public boolean removeDataWatches(String path) {
        try {
            getZkClient().removeAllWatches(path, Watcher.WatcherType.Data, false);
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
        return true;
    }

    /**
     * 移除节点下所有的监听器，含子孙节点
     *
     * @param path 节点路径
     * @return 是否成功添加
     */
    public boolean removeAllWatches(String path) {
        try {
            getZkClient().removeAllWatches(path, Watcher.WatcherType.Any, false);
        } catch (KeeperException | InterruptedException ignored) {
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        try {
            zkClient.close();
        } catch (InterruptedException ignored) {
            LOGGER.warning("Unexpected exception occurs. ");
        }
    }

    /**
     * 循环跳出处理器，目前用于处理循环注册被意外终止的情况
     *
     * @since 2021-12-15
     */
    public interface BreakHandler {
        /**
         * 处理循环注册被意外终止的情况
         *
         * @param throwable throwable
         */
        void handle(Throwable throwable);
    }
}
