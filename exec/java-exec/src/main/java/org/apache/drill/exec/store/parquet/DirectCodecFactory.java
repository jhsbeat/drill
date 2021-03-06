/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.parquet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DrillBuf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.store.parquet.DirectCodecFactory.DirectBytesDecompressor;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.DirectDecompressor;
import org.xerial.snappy.Snappy;

import parquet.bytes.ByteBufferAllocator;
import parquet.bytes.BytesInput;
import parquet.hadoop.CodecFactory;
import parquet.hadoop.CodecFactory.BytesCompressor;
import parquet.hadoop.HeapCodecFactory.HeapBytesCompressor;
import parquet.hadoop.HeapCodecFactory.HeapBytesDecompressor;
import parquet.hadoop.metadata.CompressionCodecName;

import com.google.common.base.Preconditions;

public class DirectCodecFactory extends CodecFactory<BytesCompressor, DirectBytesDecompressor> implements AutoCloseable {

  private final ByteBufferAllocator allocator;

  public DirectCodecFactory(Configuration config, ByteBufferAllocator allocator) {
    super(config);
    Preconditions.checkNotNull(allocator);
    this.allocator = allocator;
  }

  public DirectCodecFactory(Configuration config, BufferAllocator allocator) {
    this(config, new ParquetDirectByteBufferAllocator(allocator));
  }

  private ByteBuffer ensure(ByteBuffer buffer, int size) {
    if (buffer == null) {
      buffer = allocator.allocate(size);
    } else if (buffer.capacity() >= size) {
      buffer.clear();
    } else {
      allocator.release(buffer);
      release(buffer);
      buffer = allocator.allocate(size);
    }
    return buffer;
  }

  ByteBuffer release(ByteBuffer buffer) {
    if (buffer != null) {
      allocator.release(buffer);
    }
    return null;
  }

  @Override
  protected BytesCompressor createCompressor(final CompressionCodecName codecName, final CompressionCodec codec,
      int pageSize) {

    if (codec == null) {
      return new NoopCompressor();
    } else if (codecName == CompressionCodecName.SNAPPY) {
      // avoid using the Parquet Snappy codec since it allocates direct buffers at awkward spots.
      return new SnappyCompressor();
    } else {

      // todo: move zlib above since it also generates allocateDirect calls.
      return new HeapBytesCompressor(codecName, codec, pageSize);
    }
  }

  @Override
  protected DirectBytesDecompressor createDecompressor(final CompressionCodec codec) {
    // This is here so that debugging can be done if we see inconsistencies between our decompression and upstream
    // decompression.
    // if (true) {
    // return new HeapFakeDirect(codec);
    // }

    if (codec == null) {
      return new NoopDecompressor();
    } else if (DirectCodecPool.INSTANCE.codec(codec).supportsDirectDecompression()) {
      return new FullDirectDecompressor(codec);
    } else {
      return new IndirectDecompressor(codec);
    }
  }

  public void close() {
    release();
  }

  /**
   * Keeping this here for future debugging versus using custom implementations below.
   */
  private class HeapFakeDirect extends DirectBytesDecompressor {

    private final ExposedHeapBytesDecompressor innerCompressor;

    public HeapFakeDirect(CompressionCodec codec){
      innerCompressor = new ExposedHeapBytesDecompressor(codec);
    }

    @Override
    public void decompress(DrillBuf input, int compressedSize, DrillBuf output, int uncompressedSize)
        throws IOException {
      BytesInput uncompressed = decompress(new ByteBufBytesInput(input), uncompressedSize);
      output.clear();
      output.setBytes(0, uncompressed.toByteArray());
      output.writerIndex((int) uncompressed.size());
    }

    @Override
    public BytesInput decompress(BytesInput paramBytesInput, int uncompressedSize) throws IOException {
      return innerCompressor.decompress(paramBytesInput, uncompressedSize);
    }

    @Override
    protected void release() {
      innerCompressor.release();
    }

  }

  private class ExposedHeapBytesDecompressor extends HeapBytesDecompressor {
    public ExposedHeapBytesDecompressor(CompressionCodec codec) {
      super(codec);
    }

    public void release() {
      super.release();
    }
  }

  public class IndirectDecompressor extends DirectBytesDecompressor {
    private final Decompressor decompressor;

    public IndirectDecompressor(CompressionCodec codec) {
      this.decompressor = DirectCodecPool.INSTANCE.codec(codec).borrowDecompressor();
    }

    @Override
    public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
      decompressor.reset();
      byte[] inputBytes = bytes.toByteArray();
      decompressor.setInput(inputBytes, 0, inputBytes.length);
      byte[] output = new byte[uncompressedSize];
      decompressor.decompress(output, 0, uncompressedSize);
      return BytesInput.from(output);
    }

    @Override
    public void decompress(DrillBuf input, int compressedSize, DrillBuf output, int uncompressedSize)
        throws IOException {

      decompressor.reset();
      byte[] inputBytes = new byte[input.capacity()];
      input.getBytes(0, inputBytes);
      decompressor.setInput(inputBytes, 0, inputBytes.length);
      byte[] outputBytes = new byte[uncompressedSize];
      decompressor.decompress(outputBytes, 0, uncompressedSize);
      output.clear();
      output.writeBytes(outputBytes);
    }

    @Override
    protected void release() {
      DirectCodecPool.INSTANCE.returnDecompressor(decompressor);
    }
  }

  public class FullDirectDecompressor extends DirectBytesDecompressor {
    private final DirectDecompressor decompressor;
    private ByteBuffer compressedBuffer;
    private ByteBuffer uncompressedBuffer;
    private ExposedHeapBytesDecompressor extraDecompressor;
    public FullDirectDecompressor(CompressionCodec codec){
      this.decompressor = DirectCodecPool.INSTANCE.codec(codec).borrowDirectDecompressor();
      this.extraDecompressor = new ExposedHeapBytesDecompressor(codec);
    }

    @Override
    public BytesInput decompress(BytesInput compressedBytes, int uncompressedSize) throws IOException {

      if(false){
        // TODO: fix direct path. (currently, this code is causing issues when writing complex Parquet files.
        ByteBuffer bufferIn = compressedBytes.toByteBuffer();
        uncompressedBuffer = ensure(uncompressedBuffer, uncompressedSize);
        uncompressedBuffer.clear();

        if (bufferIn.isDirect()) {
          decompressor.decompress(bufferIn, uncompressedBuffer);
        } else {
          compressedBuffer = ensure(this.compressedBuffer, (int) compressedBytes.size());
          compressedBuffer.clear();
          compressedBuffer.put(bufferIn);
          compressedBuffer.flip();
          decompressor.decompress(compressedBuffer, uncompressedBuffer);
        }
        return BytesInput.from(uncompressedBuffer, 0, uncompressedSize);

      } else {
        return extraDecompressor.decompress(compressedBytes, uncompressedSize);
      }


    }


    @Override
    public void decompress(DrillBuf input, int compressedSize, DrillBuf output, int uncompressedSize)
        throws IOException {
      output.clear();
      decompressor.decompress(input.nioBuffer(0, compressedSize), output.nioBuffer(0, uncompressedSize));
      output.writerIndex(uncompressedSize);
    }

    @Override
    protected void release() {
      compressedBuffer = DirectCodecFactory.this.release(compressedBuffer);
      uncompressedBuffer = DirectCodecFactory.this.release(uncompressedBuffer);
      DirectCodecPool.INSTANCE.returnDecompressor(decompressor);
      extraDecompressor.release();
    }

  }

  public class NoopDecompressor extends DirectBytesDecompressor {

    @Override
    public void decompress(DrillBuf input, int compressedSize, DrillBuf output, int uncompressedSize)
        throws IOException {
      Preconditions.checkArgument(compressedSize == uncompressedSize,
          "Non-compressed data did not have matching compressed and uncompressed sizes.");
      output.clear();
      output.writeBytes(input, compressedSize);
    }

    @Override
    public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
      return bytes;
    }

    @Override
    protected void release() {
    }

  }

  public class SnappyCompressor extends BytesCompressor {

    private ByteBuffer incoming;
    private ByteBuffer outgoing;

    public SnappyCompressor() {
      super();
    }

    @Override
    public BytesInput compress(BytesInput bytes) throws IOException {
      int maxOutputSize = Snappy.maxCompressedLength((int) bytes.size());
      ByteBuffer bufferIn = bytes.toByteBuffer();
      outgoing = ensure(outgoing, maxOutputSize);
      final int size;
      if (bufferIn.isDirect()) {
        size = Snappy.compress(bufferIn, outgoing);
      } else {
        this.incoming = ensure(this.incoming, (int) bytes.size());
        this.incoming.put(bufferIn);
        this.incoming.flip();
        size = Snappy.compress(this.incoming, outgoing);
      }

      return BytesInput.from(outgoing, 0, (int) size);
    }

    @Override
    public CompressionCodecName getCodecName() {
      return CompressionCodecName.SNAPPY;
    }

    @Override
    protected void release() {
      outgoing = DirectCodecFactory.this.release(outgoing);
      incoming = DirectCodecFactory.this.release(incoming);
    }

  }

  public static class NoopCompressor extends BytesCompressor {

    @Override
    public BytesInput compress(BytesInput bytes) throws IOException {
      return bytes;
    }

    @Override
    public CompressionCodecName getCodecName() {
      return CompressionCodecName.UNCOMPRESSED;
    }

    @Override
    protected void release() {
    }

  }

  public static class ByteBufBytesInput extends BytesInput {
    private final ByteBuf buf;
    private final int length;

    public ByteBufBytesInput(ByteBuf buf) {
      this(buf, 0, buf.capacity());
    }

    public ByteBufBytesInput(ByteBuf buf, int offset, int length) {
      super();
      if(buf.capacity() == length && offset == 0){
        this.buf = buf;
      }else{
        this.buf = buf.slice(offset, length);
      }

      this.length = length;
    }

    @Override
    public void writeAllTo(OutputStream out) throws IOException {
      final WritableByteChannel outputChannel = Channels.newChannel(out);
      outputChannel.write(buf.nioBuffer());
    }

    @Override
    public ByteBuffer toByteBuffer() throws IOException {
      return buf.nioBuffer();
    }

    @Override
    public long size() {
      return length;
    }
  }


  public abstract class DirectBytesDecompressor extends CodecFactory.BytesDecompressor {
    public abstract void decompress(DrillBuf input, int compressedSize, DrillBuf output, int uncompressedSize)
        throws IOException;
  }



}

