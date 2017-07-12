/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.http;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.ImmutableSet;
import com.palantir.lock.StringLockDescriptor;
import com.palantir.lock.v2.ImmutableLockRequestV2;
import com.palantir.lock.v2.LockRequestV2;
import com.palantir.lock.v2.LockTokenV2;
import com.palantir.lock.v2.TimelockService;

public class AsyncLockClient implements JepsenLockClient<LockTokenV2> {
    private final TimelockService timelockService;

    private AsyncLockClient(TimelockService timelockService) {
        this.timelockService = timelockService;
    }

    public static AsyncLockClient create(List<String> hosts) {
        return new AsyncLockClient(TimelockUtils.createClient(hosts, TimelockService.class));
    }

    @Override
    public LockTokenV2 lock(String client, String lockName) throws InterruptedException {
        LockRequestV2 lockRequest = ImmutableLockRequestV2.of(
                ImmutableSet.of(StringLockDescriptor.of(lockName)), UUID.randomUUID());
        return timelockService.lock(lockRequest);
    }

    @Override
    public Set<LockTokenV2> unlock(Set<LockTokenV2> lockTokenV2s) throws InterruptedException {
        return timelockService.unlock(lockTokenV2s);
    }

    @Override
    public Set<LockTokenV2> refresh(Set<LockTokenV2> lockTokenV2s) throws InterruptedException {
        return timelockService.refreshLockLeases(lockTokenV2s);
    }
}
