package com.thaalur.quadstock.ui;

import com.thaalur.quadstock.config.TabSettings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackInfo;

/** Optional administrator-hosted pack; never exports, hosts or downloads artwork. */
public final class PortfolioPack {
  private PortfolioPack() {}

  /** Missing URL or hash keeps legacy settings usable with a text-only portfolio. */
  public static Optional<ResourcePackInfo> configured(TabSettings settings) {
    if (settings.packUrl().isEmpty() || settings.packSha1().isEmpty()) return Optional.empty();
    String hash = settings.packSha1();
    return Optional.of(
        ResourcePackInfo.resourcePackInfo()
            .id(UUID.nameUUIDFromBytes(("quadstock:" + hash).getBytes(StandardCharsets.UTF_8)))
            .uri(URI.create(settings.packUrl()))
            .hash(hash)
            .build());
  }
}
