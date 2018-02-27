/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.consumer;

import org.gradle.api.Transformer;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.PhasedBuildActionExecuter;
import org.gradle.tooling.ResultHandler;
import org.gradle.tooling.exceptions.MultipleBuildActionsException;
import org.gradle.tooling.internal.consumer.async.AsyncConsumerActionExecutor;
import org.gradle.tooling.internal.consumer.connection.ConsumerAction;
import org.gradle.tooling.internal.consumer.connection.ConsumerConnection;
import org.gradle.tooling.internal.consumer.parameters.ConsumerOperationParameters;

import javax.annotation.Nullable;

public class DefaultPhasedBuildActionExecuter extends AbstractLongRunningOperation<DefaultPhasedBuildActionExecuter> implements PhasedBuildActionExecuter {
    private final PhasedBuildAction phasedBuildAction;
    private final AsyncConsumerActionExecutor connection;

    DefaultPhasedBuildActionExecuter(PhasedBuildAction phasedBuildAction, AsyncConsumerActionExecutor connection, ConnectionParameters parameters) {
        super(parameters);
        operationParamsBuilder.setEntryPoint("PhasedBuildActionExecuter API");
        this.phasedBuildAction = phasedBuildAction;
        this.connection = connection;
    }

    @Override
    protected DefaultPhasedBuildActionExecuter getThis() {
        return this;
    }

    @Override
    public PhasedBuildActionExecuter forTasks(String... tasks) {
        operationParamsBuilder.setTasks(rationalizeInput(tasks));
        return getThis();
    }

    @Override
    public PhasedBuildActionExecuter forTasks(Iterable<String> tasks) {
        operationParamsBuilder.setTasks(rationalizeInput(tasks));
        return getThis();
    }

    @Override
    public void run()  throws GradleConnectionException, IllegalStateException {
        BlockingResultHandler<Void> handler = new BlockingResultHandler<Void>(Void.class);
        run(handler);
        handler.getResult();
    }

    @Override
    public void run(ResultHandler<? super Void> handler) throws IllegalStateException {
        final ConsumerOperationParameters operationParameters = getConsumerOperationParameters();
        connection.run(new ConsumerAction<Void>() {
            public ConsumerOperationParameters getParameters() {
                return operationParameters;
            }

            public Void run(ConsumerConnection connection) {
                connection.run(phasedBuildAction, operationParameters);
                return null;
            }
        }, new ResultHandlerAdapter<Void>(handler, new ExceptionTransformer(new Transformer<String, Throwable>() {
            @Override
            public String transform(Throwable throwable) {
                return String.format("Could not run phased build action using %s.", connection.getDisplayName());
            }
        })));
    }

    static class Builder implements PhasedBuildActionExecuter.Builder {
        @Nullable private PhasedBuildAction.BuildActionWrapper<?> afterLoadingAction = null;
        @Nullable private PhasedBuildAction.BuildActionWrapper<?> afterConfigurationAction = null;
        @Nullable private PhasedBuildAction.BuildActionWrapper<?> afterBuildAction = null;

        private final AsyncConsumerActionExecutor connection;
        private final ConnectionParameters parameters;

        Builder(AsyncConsumerActionExecutor connection, ConnectionParameters parameters) {
            this.connection = connection;
            this.parameters = parameters;
        }

        @Override
        public <T> Builder addAfterLoadingAction(BuildAction<T> action, ResultHandler<? super T> handler) throws MultipleBuildActionsException {
            if (afterLoadingAction != null) {
                throw getException("AfterLoadingAction");
            }
            afterLoadingAction = new DefaultPhasedBuildAction.DefaultBuildActionWrapper<T>(action, handler);
            return Builder.this;
        }

        @Override
        public <T> Builder addAfterConfigurationAction(BuildAction<T> action, ResultHandler<? super T> handler) throws MultipleBuildActionsException {
            if (afterConfigurationAction != null) {
                throw getException("AfterConfigurationAction");
            }
            afterConfigurationAction = new DefaultPhasedBuildAction.DefaultBuildActionWrapper<T>(action, handler);
            return Builder.this;
        }

        @Override
        public <T> Builder addAfterBuildAction(BuildAction<T> action, ResultHandler<? super T> handler) throws MultipleBuildActionsException {
            if (afterBuildAction != null) {
                throw getException("AfterBuildAction");
            }
            afterBuildAction = new DefaultPhasedBuildAction.DefaultBuildActionWrapper<T>(action, handler);
            return Builder.this;
        }

        @Override
        public PhasedBuildActionExecuter build() {
            return new DefaultPhasedBuildActionExecuter(new DefaultPhasedBuildAction(afterLoadingAction, afterConfigurationAction, afterBuildAction), connection, parameters);
        }

        private static MultipleBuildActionsException getException(String phase) {
            return new MultipleBuildActionsException(String.format("%s has already been added. Only one action per phase is allowed.", phase));
        }
    }
}
