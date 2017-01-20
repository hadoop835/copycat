/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.server.storage.system;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.storage.Storage;
import io.atomix.copycat.server.storage.StorageLevel;
import io.atomix.copycat.server.storage.buffer.Buffer;
import io.atomix.copycat.server.storage.buffer.FileBuffer;
import io.atomix.copycat.server.storage.buffer.HeapBuffer;
import io.atomix.copycat.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collection;

/**
 * Manages persistence of server configurations.
 * <p>
 * The server metastore is responsible for persisting server configurations according to the configured
 * {@link Storage#level() storage level}. Each server persists their current {@link #loadTerm() term}
 * and last {@link #loadVote() vote} as is dictated by the Raft consensus algorithm. Additionally, the
 * metastore is responsible for storing the last know server {@link Configuration}, including cluster
 * membership.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class MetaStore implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetaStore.class);
  private final Storage storage;
  private final Kryo serializer;
  private final Buffer buffer;

  public MetaStore(String name, Storage storage, Kryo serializer) {
    this.storage = Assert.notNull(storage, "storage");
    this.serializer = Assert.notNull(serializer, "serializer");
    if (storage.level() == StorageLevel.MEMORY) {
      buffer = HeapBuffer.allocate(12);
    } else {
      storage.directory().mkdirs();
      File file = new File(storage.directory(), String.format("%s.meta", name));
      buffer = FileBuffer.allocate(file, 12);
    }
  }

  /**
   * Returns the metastore serializer.
   *
   * @return The metastore serializer.
   */
  public Kryo serializer() {
    return serializer;
  }

  /**
   * Stores the current server term.
   *
   * @param term The current server term.
   * @return The metastore.
   */
  public synchronized MetaStore storeTerm(long term) {
    LOGGER.debug("Store term {}", term);
    buffer.writeLong(0, term).flush();
    return this;
  }

  /**
   * Loads the stored server term.
   *
   * @return The stored server term.
   */
  public synchronized long loadTerm() {
    return buffer.readLong(0);
  }

  /**
   * Stores the last voted server.
   *
   * @param vote The server vote.
   * @return The metastore.
   */
  public synchronized MetaStore storeVote(int vote) {
    LOGGER.debug("Store vote {}", vote);
    buffer.writeInt(8, vote).flush();
    return this;
  }

  /**
   * Loads the last vote for the server.
   *
   * @return The last vote for the server.
   */
  public synchronized int loadVote() {
    return buffer.readInt(8);
  }

  /**
   * Stores the current cluster configuration.
   *
   * @param configuration The current cluster configuration.
   * @return The metastore.
   */
  public synchronized MetaStore storeConfiguration(Configuration configuration) {
    LOGGER.debug("Store configuration {}", configuration);

    buffer.position(12)
      .writeByte(1)
      .writeLong(configuration.index())
      .writeLong(configuration.term())
      .writeLong(configuration.time());

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Output output = new Output(outputStream);
    serializer.writeClassAndObject(output, configuration.members());
    output.flush();
    byte[] bytes = outputStream.toByteArray();
    buffer.write(bytes);
    buffer.flush();
    return this;
  }

  /**
   * Loads the current cluster configuration.
   *
   * @return The current cluster configuration.
   */
  @SuppressWarnings("unchecked")
  public synchronized Configuration loadConfiguration() {
    if (buffer.position(12).readByte() == 1) {
      long index = buffer.readLong();
      long term = buffer.readLong();
      long time = buffer.readLong();

      byte[] bytes = new byte[(int) buffer.remaining()];
      buffer.read(bytes);
      Collection<Member> members = (Collection<Member>) serializer.readClassAndObject(new Input(bytes));
      return new Configuration(index, term, time, members);
    }
    return null;
  }

  @Override
  public synchronized void close() {
    buffer.close();
  }

  @Override
  public String toString() {
    if (buffer instanceof FileBuffer) {
      return String.format("%s[%s]", getClass().getSimpleName(), ((FileBuffer) buffer).file());
    } else {
      return getClass().getSimpleName();
    }
  }

}
