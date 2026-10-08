package io.github.httpmock;

import java.util.*;
import java.util.function.*;

public final class SuiteRunner {
    /** Result index refers to the supplied list, preserving duplicates and order. */
    public List<CaseResult> run(ClientConfig config, List<ErrorType> types,
                                Consumer<RunEvent> events, BiConsumer<Integer, CaseResult> completed) {
        List<CaseResult> results = new ArrayList<>();
        ScenarioRunner runner = new ScenarioRunner();
        for (int i = 0; i < types.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;
            CaseResult result = runner.run(config, types.get(i), events);
            results.add(result);
            completed.accept(i, result);
            if (result.outcome() == CaseResult.Outcome.CANCELLED) break;
            if (i + 1 < types.size()) {
                try { Thread.sleep(config.delay()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return List.copyOf(results);
    }
}
