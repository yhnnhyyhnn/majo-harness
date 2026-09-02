package io.majo.harness.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisposablesTest {

    @Test
    void compositeRevertsInReverseOrderAndSkipsNulls() {
        List<String> order = new ArrayList<>();
        Disposable first = () -> order.add("first");
        Disposable second = () -> order.add("second");
        Disposable third = () -> order.add("third");

        Disposable composite = Disposables.composite(first, null, second, third);
        composite.dispose();

        assertThat(order).containsExactly("third", "second", "first");
    }

    @Test
    void emptyCompositeIsSafe() {
        Disposables.composite().dispose();
        Disposables.composite(List.of()).dispose();
        Disposables.composite((Disposable) null).dispose();
    }
}
