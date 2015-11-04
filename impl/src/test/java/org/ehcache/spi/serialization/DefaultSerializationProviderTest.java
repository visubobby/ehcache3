/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehcache.spi.serialization;

import java.io.IOException;
import java.io.Serializable;
import static java.lang.ClassLoader.getSystemClassLoader;
import org.ehcache.config.serializer.DefaultSerializerConfiguration;
import org.ehcache.config.serializer.DefaultSerializationProviderConfiguration;
import org.ehcache.internal.serialization.CompactJavaSerializer;
import org.ehcache.internal.serialization.CompactPersistentJavaSerializer;
import org.ehcache.spi.ServiceProvider;
import org.junit.Test;

import java.nio.ByteBuffer;
import static org.ehcache.spi.TestServiceProvider.providerContaining;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import org.hamcrest.core.IsInstanceOf;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Ludovic Orban
 */
public class DefaultSerializationProviderTest {

  @Test
  public void testCreateSerializerNoConfig() throws Exception {
    DefaultSerializationProviderConfiguration dspfConfig = new DefaultSerializationProviderConfiguration();
    DefaultSerializationProvider dsp = new DefaultSerializationProvider(dspfConfig);
    dsp.start(providerContaining());

    assertThat(dsp.createValueSerializer(String.class, ClassLoader.getSystemClassLoader()), instanceOf(CompactJavaSerializer.class));
    try {
      dsp.createValueSerializer(Object.class, ClassLoader.getSystemClassLoader());
      fail("expected UnsupportedTypeException");
    } catch (UnsupportedTypeException ute) {
      // expected
    }
  }

  @Test
  public void testCreateSerializerWithConfig() throws Exception {
    DefaultSerializationProviderConfiguration dspfConfig = new DefaultSerializationProviderConfiguration();
    DefaultSerializationProvider dsp = new DefaultSerializationProvider(dspfConfig);
    dsp.start(providerContaining());

    DefaultSerializerConfiguration dspConfig = new DefaultSerializerConfiguration((Class) TestSerializer.class, DefaultSerializerConfiguration.Type.VALUE);

    assertThat(dsp.createValueSerializer(String.class, ClassLoader.getSystemClassLoader(), dspConfig), instanceOf(TestSerializer.class));
    assertThat(dsp.createValueSerializer(Object.class, ClassLoader.getSystemClassLoader(), dspConfig), instanceOf(TestSerializer.class));
  }

  @Test
  public void testCreateSerializerWithFactoryConfig() throws Exception {
    DefaultSerializationProviderConfiguration dspfConfig = new DefaultSerializationProviderConfiguration();
    dspfConfig.addSerializerFor(Number.class, (Class) TestSerializer.class);
    DefaultSerializationProvider dsp = new DefaultSerializationProvider(dspfConfig);
    dsp.start(providerContaining());

    assertThat(dsp.createValueSerializer(Long.class, ClassLoader.getSystemClassLoader()), instanceOf(TestSerializer.class));
    assertThat(dsp.createValueSerializer(String.class, ClassLoader.getSystemClassLoader()), instanceOf(CompactJavaSerializer.class));
  }

  @Test
  public void testCreateTransientSerializers() throws Exception {
    DefaultSerializationProviderConfiguration dspfConfig = new DefaultSerializationProviderConfiguration();
    dspfConfig.addSerializerFor(String.class, (Class) TestSerializer.class);

    DefaultSerializationProvider dsp = new DefaultSerializationProvider(dspfConfig);
    dsp.start(providerContaining());

    assertThat(dsp.createKeySerializer(String.class, getSystemClassLoader()), instanceOf(TestSerializer.class));
    assertThat(dsp.createKeySerializer(Serializable.class, getSystemClassLoader()), instanceOf(CompactJavaSerializer.class));
    assertThat(dsp.createKeySerializer(Integer.class, getSystemClassLoader()), instanceOf(CompactJavaSerializer.class));
  }

  @Test
  public void tesCreateTransientSerializersWithOverriddenSerializableType() throws Exception {
    DefaultSerializationProviderConfiguration dspfConfig = new DefaultSerializationProviderConfiguration();
    dspfConfig.addSerializerFor(Serializable.class, (Class) TestSerializer.class);

    DefaultSerializationProvider dsp = new DefaultSerializationProvider(dspfConfig);
    dsp.start(providerContaining());

    assertThat(dsp.createKeySerializer(String.class, getSystemClassLoader()), instanceOf(TestSerializer.class));
    assertThat(dsp.createKeySerializer(Serializable.class, getSystemClassLoader()), instanceOf(TestSerializer.class));
    assertThat(dsp.createKeySerializer(Integer.class, getSystemClassLoader()), instanceOf(TestSerializer.class));
  }

  @Test
  public void testRemembersCreationConfigurationAfterStopStart() throws UnsupportedTypeException {
    DefaultSerializationProviderConfiguration configuration = new DefaultSerializationProviderConfiguration();
    configuration.addSerializerFor(String.class, (Class) TestSerializer.class);
    DefaultSerializationProvider serializationProvider = new DefaultSerializationProvider(configuration);
    serializationProvider.start(mock(ServiceProvider.class));
    assertThat(serializationProvider.createKeySerializer(String.class, getSystemClassLoader()), instanceOf(TestSerializer.class));
    serializationProvider.stop();
    serializationProvider.start(mock(ServiceProvider.class));
    assertThat(serializationProvider.createKeySerializer(String.class, getSystemClassLoader()), instanceOf(TestSerializer.class));
  }

  @Test
  public void testReleaseSerializerWithCompactPersistentJavaSerializer() throws Exception {
    DefaultSerializationProvider provider = new DefaultSerializationProvider(null);
    CompactPersistentJavaSerializer<?> serializer = mock(CompactPersistentJavaSerializer.class);
    provider.created.add(serializer);
    
    provider.releaseSerializer(serializer);
    verify(serializer).close();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testReleaseSerializerByAnotherProvider() throws Exception {
    DefaultSerializationProvider provider = new DefaultSerializationProvider(null);
    Serializer<?> serializer = mock(Serializer.class);
    provider.releaseSerializer(serializer);
  }

  @Test(expected = RuntimeException.class)
  public void testReleaseSerializerThrowsOnCompactPersistentJavaSerializerClose() throws Exception {
    DefaultSerializationProvider provider = new DefaultSerializationProvider(null);
    CompactPersistentJavaSerializer<?> serializer = mock(CompactPersistentJavaSerializer.class);
    doThrow(IOException.class).when(serializer).close();
    provider.created.add(serializer);

    provider.releaseSerializer(serializer);
  }
  public static class TestSerializer<T> implements Serializer<T> {
    public TestSerializer(ClassLoader classLoader) {
    }
    @Override
    public ByteBuffer serialize(T object) {
      return null;
    }
    @Override
    public T read(ByteBuffer binary) {
      return null;
    }
    @Override
    public boolean equals(T object, ByteBuffer binary) {
      return false;
    }
  }

}
