package github.landminehq.satoribot;

import java.util.List;

public interface RelayConfig {
    List<String> groupIds();

    String prefix();

    int mergeWindowSeconds();

    String satoriToken();

    String satoriUrl();
}
