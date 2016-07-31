/*
 * Copyright 2016 Zbynek Vyskovsky mailto:kvr000@gmail.com http://github.com/kvr000/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.kvr000.adaptivezip.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;


/**
 * CRC32 and size calculating FilterInputStream.
 */
public class Crc32CalculatingInputStream extends FilterInputStream
{
	public Crc32CalculatingInputStream(InputStream i) {
		super(i);
	}

	public int getCrc32() {
		return (int) crc.getValue();
	}

	public long getSize()
	{
		return size;
	}

	@Override
	public int read() throws IOException
	{
		int b = in.read();
		if (b >= 0) {
			crc.update(b);
			size += 1;
		}
		return b;
	}

	@Override
	public int read(byte[] b, int o, int l) throws IOException
	{
		int r = in.read(b, o, l);
		if (r > 0) {
			crc.update(b, o, r);
			size += r;
		}
		return r;
	}

	@Override
	public int read(byte[] b) throws IOException
	{
		int r = in.read(b);
		if (r > 0) {
			crc.update(b, 0, r);
			size += r;
		}
		return r;
	}

	final CRC32 crc = new CRC32();

	long size;
}
