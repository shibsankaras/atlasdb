/**
 * Copyright 2016 Palantir Technologies
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
package com.palantir.atlasdb.cas;

import com.google.common.base.Optional;
import com.palantir.atlasdb.transaction.api.TransactionManager;

public class CheckAndSetClient {
    private final TransactionManager transactionManager;

    public CheckAndSetClient(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public int getInt() {
        return (get().or(-1L)).intValue();
    }

    public Optional<Long> get() {
        return transactionManager.runTaskReadOnly((transaction) -> new CheckAndSetPersistentValue(transaction).get());
    }

    public void setInt(int value) {
        set(Optional.of((long) value));
    }

    public void set(Optional<Long> value) {
        transactionManager.runTaskWithRetry((transaction) -> {
            new CheckAndSetPersistentValue(transaction).set(value);
            return null;
        });
    }

    public boolean checkAndSetInt(int oldValue, int newValue) {
        return checkAndSet(Optional.of((long) oldValue), Optional.of((long) newValue));
    }

    public boolean checkAndSet(Optional<Long> oldValue, Optional<Long> newValue) {
        return transactionManager.runTaskWithRetry((transaction) -> new CheckAndSetPersistentValue(transaction).checkAndSet(oldValue, newValue));
    }
}