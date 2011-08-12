/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.nodex.core.shared;

import org.cliffc.high_scale_lib.NonBlockingHashSet;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class SharedSet<E> implements Set<E> {

  private static final Map<String, Set<?>> refs = new WeakHashMap<>();

  private final Set<E> set;

  public SharedSet(String name) {
    synchronized (refs) {
      Set<E> s = (Set<E>) refs.get(name);
      if (s == null) {
        s = new NonBlockingHashSet<>();
        refs.put(name, s);
      }
      set = s;
    }
  }

  public int size() {
    return set.size();
  }

  public boolean isEmpty() {
    return set.isEmpty();
  }

  public boolean contains(Object o) {
    return set.contains(o);
  }

  public Iterator<E> iterator() {
    return set.iterator();
  }

  public Object[] toArray() {
    return set.toArray();
  }

  public <T> T[] toArray(T[] ts) {
    return set.toArray(ts);
  }

  public boolean add(E e) {
    return set.add(e);
  }

  public boolean remove(Object o) {
    return set.remove(o);
  }

  public boolean containsAll(Collection<?> objects) {
    return set.containsAll(objects);
  }

  public boolean addAll(Collection<? extends E> es) {
    return set.addAll(es);
  }

  public boolean retainAll(Collection<?> objects) {
    return set.retainAll(objects);
  }

  public boolean removeAll(Collection<?> objects) {
    return set.removeAll(objects);
  }

  public void clear() {
    set.clear();
  }

  @Override
  public boolean equals(Object o) {
    return set.equals(o);
  }

  @Override
  public int hashCode() {
    return set.hashCode();
  }
}