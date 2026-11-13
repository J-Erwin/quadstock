package com.thaalur.quadstock.display;

import com.thaalur.quadstock.persistence.StateStore;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Bounded versioned SQLite checkpoint. Changes publish only after a successful commit. */
public final class DisplayStore {
  /** A storage failure differs from an ordinary rejected stale editor. */
  public static final class StorageException extends IOException {
    public StorageException(IOException cause) {
      super("Cannot persist displays; restart required", cause);
    }
  }

  private final Path directory;
  private Map<UUID, DisplayDefinition> definitions;

  public DisplayStore(Path directory) throws IOException {
    this.directory = directory;
    definitions = decode(StateStore.read(directory, "physical-displays").orElse(null));
  }

  public Map<UUID, DisplayDefinition> all() {
    return Collections.unmodifiableMap(definitions);
  }

  public void put(DisplayDefinition next, long expected) throws IOException {
    var old = definitions.get(next.id());
    if ((old == null ? 0 : old.revision()) != expected)
      throw new IOException("Display changed; reopen its editor");
    for (var other : definitions.values())
      if (!other.id().equals(next.id())
          && other.anchor().locationKey().equals(next.anchor().locationKey()))
        throw new IOException("This block already owns a display (possibly pending removal)");
    var copy = new LinkedHashMap<>(definitions);
    copy.put(next.id(), next);
    commit(copy);
  }

  public void forget(UUID id) throws IOException {
    var copy = new LinkedHashMap<>(definitions);
    copy.remove(id);
    commit(copy);
  }

  private void commit(Map<UUID, DisplayDefinition> copy) throws IOException {
    byte[] bytes = encode(copy);
    try {
      StateStore.write(directory, "physical-displays", bytes);
    } catch (IOException e) {
      throw new StorageException(e);
    }
    definitions = copy;
  }

  static byte[] encode(Map<UUID, DisplayDefinition> values) throws IOException {
    if (values.size() > 4096) throw new IOException("Too many display records");
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(1);
      out.writeInt(values.size());
      for (var d : values.values()) {
        out.writeUTF(d.id().toString());
        var a = d.anchor();
        out.writeUTF(a.world().toString());
        out.writeInt(a.x());
        out.writeInt(a.y());
        out.writeInt(a.z());
        out.writeUTF(a.material());
        out.writeUTF(a.blockData());
        out.writeLong(d.revision());
        out.writeBoolean(d.removed());
        writeMap(out, d.options());
        writeMap(out, d.originals());
      }
    }
    if (bytes.size() > 16_000_000) throw new IOException("Display checkpoint too large");
    return bytes.toByteArray();
  }

  static Map<UUID, DisplayDefinition> decode(byte[] bytes) throws IOException {
    var result = new LinkedHashMap<UUID, DisplayDefinition>();
    if (bytes == null) return result;
    if (bytes.length > 16_000_000) throw new IOException("Display checkpoint too large");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != 1) throw new IOException("Unsupported display checkpoint version");
      int count = in.readInt();
      if (count < 0 || count > 4096) throw new IOException("Invalid display count");
      var anchors = new HashSet<String>();
      for (int i = 0; i < count; i++) {
        var id = UUID.fromString(in.readUTF());
        var a =
            new DisplayDefinition.Anchor(
                UUID.fromString(in.readUTF()),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readUTF(),
                in.readUTF());
        long rev = in.readLong();
        boolean removed = in.readBoolean();
        var d = new DisplayDefinition(id, a, rev, readMap(in), readMap(in), removed);
        if (result.put(id, d) != null || !anchors.add(a.locationKey()))
          throw new IOException("Duplicate display identity/anchor");
      }
      if (in.available() != 0) throw new IOException("Trailing display data");
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Corrupt display checkpoint", e);
    }
    return result;
  }

  private static void writeMap(DataOutputStream out, Map<String, String> map) throws IOException {
    out.writeInt(map.size());
    for (var e : new TreeMap<>(map).entrySet()) {
      out.writeUTF(e.getKey());
      out.writeUTF(e.getValue());
    }
  }

  private static Map<String, String> readMap(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > 32) throw new IOException("Invalid display map");
    var m = new HashMap<String, String>();
    for (int i = 0; i < n; i++)
      if (m.put(in.readUTF(), in.readUTF()) != null)
        throw new IOException("Duplicate display property");
    return m;
  }
}
