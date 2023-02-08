// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A {@link SkyKey} is effectively a pair (type, name) that identifies a Skyframe value.
 *
 * <p>SkyKey implementations are heavily used as map keys. Thus, they should have fast {@link
 * #hashCode} implementations (cached if necessary). The same SkyKey may be created multiple times
 * by different {@code SkyFunction}s requesting it, and so it should have effective interning. There
 * will likely be more SkyKeys on the JVM heap than any other non-native type, so be mindful of
 * memory usage (in particular object wrapper size and memory alignment)! Typically the
 * implementation should have a fixed {@link #functionName} implementation and return itself as the
 * {@link #argument} in order to reduce the cost of wrapper objects.
 */
public interface SkyKey extends Serializable {
  SkyFunctionName functionName();

  default Object argument() {
    return this;
  }

  /**
   * Returns {@code true} if this key produces a {@link SkyValue} that can be reused across builds.
   *
   * <p>Values may be unshareable because they are just not serializable, or because they contain
   * data that cannot safely be reused as-is by another invocation, such as stamping information or
   * "flaky" values like test statuses.
   *
   * <p>Unshareable data should not be serialized, since it will never be reused. Attempts to fetch
   * a key's serialized data will call this method and only perform the fetch if it returns {@code
   * true}.
   *
   * <p>The result of this method only applies to non-error values. In case of an error, {@link
   * ErrorInfo#isTransitivelyTransient()} can be used to determine shareability.
   */
  default boolean valueIsShareable() {
    return true;
  }

  /**
   * Returns {@code true} if this key's {@link SkyFunction} would like Skyframe to schedule its
   * reevaluation when any of its previously requested unfinished deps completes. Otherwise,
   * Skyframe will schedule reevaluation only when all previously requested unfinished deps
   * complete.
   */
  default boolean supportsPartialReevaluation() {
    return false;
  }

  static <T extends SkyKey> SkyKeyInterner<T> newInterner() {
    return new SkyKeyInterner<>();
  }

  /**
   * An alternative container to the weak interner for storing {@link SkyKey}.
   *
   * <p>A pool is a storage space that already exists during normal program execution and provides
   * lookup functionality for interning, thus eliminating storage overhead from using a classic weak
   * interner.
   */
  interface SkyKeyPool {

    /**
     * Returns the canonical instance of the {@link SkyKey} if found in the pool, otherwise returns
     * {@code null}.
     */
    @Nullable
    SkyKey canonicalize(SkyKey key);
  }

  /**
   * An extension of the weak interner which contains an alternative and static {@link #globalPool}
   * container in addition to a weak interner and supports removing {@link SkyKey} from the {@link
   * #weakInterner}.
   *
   * <p>The reason of implementing {@link SkyKeyInterner} is that the same {@link SkyKey} object can
   * be stored in both weak interner and some other container in blaze with two equal references,
   * causing some memory overhead.
   *
   * <p>{@link SkyKeyInterner} enables the client to manage where the {@link SkyKey} object is
   * stored, addressing the memory overhead issue. In more detail,
   *
   * <ul>
   *   <li>If the {@link SkyKey} object is already canonicalized in the {@link #globalPool}, it
   *       should not be stored in {@link #weakInterner} again, thus removing the storage overhead
   *       of using a weak interner;
   *   <li>User can also remove {@link SkyKey} from {@link #weakInterner}'s underlying {@link
   *       #internerAsMap} when the {@link SkyKey} object appears in {@link #globalPool}.
   * </ul>
   */
  final class SkyKeyInterner<T extends SkyKey> implements Interner<T> {
    @Nullable private static SkyKeyPool globalPool = null;

    private final Interner<T> weakInterner = BlazeInterners.newWeakInterner();
    private final Map<?, ?> internerAsMap = getMapReflectively(weakInterner);

    private SkyKeyInterner() {}

    // There was a Guava API review to include the feature of removing from an interner, and the
    // outcome was that we should just get the map reflectively.
    private static Map<?, ?> getMapReflectively(Interner<?> interner) {
      try {
        Field field = interner.getClass().getDeclaredField("map");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(interner);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * Sets the {@link SkyKeyPool} to be used for interning.
     *
     * <p>The pool is strongly retained until another pool is set. {@code null} can be passed to
     * clear the global pool.
     */
    @ThreadSafety.ThreadCompatible
    public static void setGlobalPool(@Nullable SkyKeyPool pool) {
      // No synchronization is needed. Setting global pool is guaranteed to happen sequentially
      // since only one build can happen at the same time.
      globalPool = pool;
    }

    /**
     * Returns the canonical instance of {@code sample} from either {@link #globalPool} or the
     * {@link #weakInterner}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public T intern(T sample) {
      SkyKeyPool pool = globalPool;
      if (pool != null) {
        SkyKey result = pool.canonicalize(sample);
        if (result != null) {
          return (T) result;
        }
      }
      return weakInterner.intern(sample);
    }

    /**
     * Removes {@link SkyKey} from the weak interner. Client can call this method when the {@link
     * SkyKey} is already stored in the global pool in order to reduce the memory overhead.
     */
    public void removeWeak(SkyKey sample) {
      internerAsMap.remove(sample);
    }
  }
}
