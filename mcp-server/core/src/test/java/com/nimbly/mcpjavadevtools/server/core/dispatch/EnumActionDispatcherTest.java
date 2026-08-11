package com.nimbly.mcpjavadevtools.server.core.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnumActionDispatcherTest {

    @Test
    void dispatchesTheHandlerRegisteredForTheSelectedAction() {
        EnumActionDispatcher<Action, String, String> dispatcher = dispatcher(handler(Action.FIRST, "first"));

        assertThat(dispatcher.dispatch(Action.FIRST, "input")).isEqualTo("first:input");
    }

    @Test
    void rejectsDuplicateActionRegistration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EnumActionDispatcher<>(
                        Action.class,
                        List.of(handler(Action.FIRST, "one"), handler(Action.FIRST, "two"))))
                .withMessage("duplicate action handler: FIRST");
    }

    @Test
    void rejectsMissingActionRegistration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EnumActionDispatcher<>(Action.class, List.of(handler(Action.FIRST, "one"))))
                .withMessage("missing action handler: SECOND");
    }

    @Test
    void rejectsNullHandlersAndNullHandlerActions() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EnumActionDispatcher<Action, String, String>(
                        Action.class,
                        java.util.Collections.singletonList(null)))
                .withMessage("handlers must not contain null");
        assertThatNullPointerException()
                .isThrownBy(() -> new EnumActionDispatcher<Action, String, String>(
                        Action.class,
                        List.of(new ActionHandler<>() {
                            @Override
                            public Action action() {
                                return null;
                            }

                            @Override
                            public String execute(String input) {
                                return input;
                            }
                        })))
                .withMessage("handler action must not be null");
    }

    @Test
    void rejectsNullDispatchActionsDeterministically() {
        EnumActionDispatcher<Action, String, String> dispatcher = new EnumActionDispatcher<>(
                Action.class,
                List.of(handler(Action.FIRST, "one"), handler(Action.SECOND, "two")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> dispatcher.dispatch(null, "input"))
                .withMessage("unsupported action: null");
    }

    private static EnumActionDispatcher<Action, String, String> dispatcher(ActionHandler<Action, String, String> first) {
        return new EnumActionDispatcher<>(Action.class, List.of(first, handler(Action.SECOND, "second")));
    }

    private static ActionHandler<Action, String, String> handler(Action action, String prefix) {
        return new ActionHandler<>() {
            @Override
            public Action action() {
                return action;
            }

            @Override
            public String execute(String input) {
                return prefix + ":" + input;
            }
        };
    }

    private enum Action {
        FIRST,
        SECOND
    }
}
