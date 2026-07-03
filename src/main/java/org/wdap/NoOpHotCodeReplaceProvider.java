package org.wdap;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.microsoft.java.debug.core.adapter.HotCodeReplaceEvent;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

/**
 * No-op HCR provider. Hot code replace is handled externally (IntelliJ / jdb redefine).
 */
public class NoOpHotCodeReplaceProvider implements IHotCodeReplaceProvider {

    private final PublishSubject<HotCodeReplaceEvent> eventSubject = PublishSubject.create();

    @Override
    public void onClassRedefined(Consumer<List<String>> consumer) {
        // no-op
    }

    @Override
    public CompletableFuture<List<String>> redefineClasses() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public Observable<HotCodeReplaceEvent> getEventHub() {
        return eventSubject;
    }
}
