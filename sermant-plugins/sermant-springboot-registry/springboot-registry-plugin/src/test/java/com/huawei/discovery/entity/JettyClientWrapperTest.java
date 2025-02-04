/*
 * Copyright (C) 2023-2023 Huawei Technologies Co., Ltd. All rights reserved.
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

package com.huawei.discovery.entity;

import com.huawei.discovery.config.LbConfig;
import com.huawei.discovery.utils.HttpConstants;

import com.huaweicloud.sermant.core.plugin.config.PluginConfigManager;
import com.huaweicloud.sermant.core.utils.ReflectUtils;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.api.Response.CompleteListener;
import org.eclipse.jetty.client.api.Result;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.ConnectException;
import java.net.URI;

/**
 * 测试JettyClientWrapper
 *
 * @author provenceee
 * @since 2023-05-17
 */
public class JettyClientWrapperTest {
    private static MockedStatic<PluginConfigManager> mockPluginConfigManager;

    private final JettyClientWrapper wrapper;

    private final HttpConversation conversation;

    @BeforeClass
    public static void before() throws Exception {
        mockPluginConfigManager = Mockito.mockStatic(PluginConfigManager.class);
        mockPluginConfigManager.when(() -> PluginConfigManager.getPluginConfig(LbConfig.class))
                .thenReturn(new LbConfig());
    }

    @AfterClass
    public static void after() {
        mockPluginConfigManager.close();
    }

    public JettyClientWrapperTest() {
        HttpClient client = Mockito.mock(HttpClient.class);
        conversation = new HttpConversation();
        wrapper = new JettyClientWrapper(client, conversation, URI.create("http://www.domain.com/foo/hello"));

        // 由于new JettyClientWrapper时的host与HttpClient有关,且HttpClient为mock对象,所以需要手动设置一下host
        ReflectUtils.setFieldValue(wrapper, HttpConstants.HTTP_URI_HOST, "www.domain.com");
        ReflectUtils.setFieldValue(wrapper, "originHost", "www.domain.com");
    }

    @Test
    public void test() {
        CompleteListener listener = new TestCompleteListener();

        // 测试send方法
        wrapper.send(listener);
        Assert.assertEquals(listener, ReflectUtils.getFieldValue(wrapper, "originCompleteListener").orElse(null));

        // 模拟send方法之后的数据更新
        conversation.updateResponseListeners(new TestCompleteListener());
        conversation.updateResponseListeners(listener);
        ReflectUtils.setFieldValue(wrapper, HttpConstants.HTTP_URI_HOST, "127.0.0.1");
        ReflectUtils.setFieldValue(wrapper, HttpConstants.HTTP_URI_PORT, 8080);
        ReflectUtils.setFieldValue(wrapper, HttpConstants.HTTP_URI_PATH, "hello");

        // 测试abort方法
        wrapper.abort(new ConnectException());
        Assert.assertTrue(conversation.getExchanges().isEmpty());
        Assert.assertTrue(conversation.getResponseListeners().isEmpty());
        Assert.assertEquals("www.domain.com", wrapper.getHost());
        Assert.assertEquals("/foo/hello", wrapper.getPath());
        Assert.assertEquals(80, wrapper.getPort());
        Assert.assertEquals(listener, ReflectUtils.getFieldValue(wrapper, "originCompleteListener").orElse(null));
    }

    public static class TestCompleteListener implements CompleteListener {
        @Override
        public void onComplete(Result result) {
        }
    }
}