/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.user;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.jcr.Session;

import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.spi.commit.ValidatorProvider;
import org.apache.jackrabbit.oak.spi.security.user.MembershipProvider;
import org.apache.jackrabbit.oak.spi.security.user.UserConfig;
import org.apache.jackrabbit.oak.spi.security.user.UserContext;
import org.apache.jackrabbit.oak.spi.security.user.UserProvider;

/**
 * UserContextImpl... TODO
 */
public class UserContextImpl implements UserContext {

    private final UserConfig config;

    // TODO add proper configuration
    public UserContextImpl() {
        this(new UserConfig());
    }

    public UserContextImpl(UserConfig config) {
        this.config = config;
    }

    @Nonnull
    @Override
    public UserConfig getUserConfig() {
        return config;
    }

    @Override
    public UserProvider getUserProvider(Root root) {
        return new UserProviderImpl(root, config);
    }

    @Override
    public MembershipProvider getMembershipProvider(Root root) {
        return new MembershipProviderImpl(root, config);
    }

    @Override
    public List<ValidatorProvider> getValidatorProviders() {
        ValidatorProvider vp = new UserValidatorProvider(config);
        return Collections.singletonList(vp);
    }

    @Override
    public UserManager getUserManager(Session session, Root root, NamePathMapper namePathMapper) {
        UserProvider up = getUserProvider(root);
        MembershipProvider mp = getMembershipProvider(root);
        return new UserManagerImpl(session, namePathMapper, up, mp, config);
    }
}