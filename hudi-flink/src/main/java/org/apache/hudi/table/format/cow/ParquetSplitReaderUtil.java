/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.format.cow;

import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.table.format.cow.vector.HeapArrayVector;
import org.apache.hudi.table.format.cow.vector.HeapMapColumnVector;
import org.apache.hudi.table.format.cow.vector.HeapRowColumnVector;
import org.apache.hudi.table.format.cow.vector.ParquetDecimalVector;
import org.apache.hudi.table.format.cow.vector.reader.ArrayColumnReader;
import org.apache.hudi.table.format.cow.vector.reader.FixedLenBytesColumnReader;
import org.apache.hudi.table.format.cow.vector.reader.Int64TimestampColumnReader;
import org.apache.hudi.table.format.cow.vector.reader.MapColumnReader;
import org.apache.hudi.table.format.cow.vector.reader.ParquetColumnarRowSplitReader;
import org.apache.hudi.table.format.cow.vector.reader.RowColumnReader;

import org.apache.flink.core.fs.Path;
import org.apache.flink.formats.parquet.vector.reader.BooleanColumnReader;
import org.apache.flink.formats.parquet.vector.reader.ByteColumnReader;
import org.apache.flink.formats.parquet.vector.reader.BytesColumnReader;
import org.apache.flink.formats.parquet.vector.reader.ColumnReader;
import org.apache.flink.formats.parquet.vector.reader.DoubleColumnReader;
import org.apache.flink.formats.parquet.vector.reader.FloatColumnReader;
import org.apache.flink.formats.parquet.vector.reader.IntColumnReader;
import org.apache.flink.formats.parquet.vector.reader.LongColumnReader;
import org.apache.flink.formats.parquet.vector.reader.ShortColumnReader;
import org.apache.flink.formats.parquet.vector.reader.TimestampColumnReader;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.data.vector.ColumnVector;
import org.apache.flink.table.data.vector.VectorizedColumnBatch;
import org.apache.flink.table.data.vector.heap.HeapBooleanVector;
import org.apache.flink.table.data.vector.heap.HeapByteVector;
import org.apache.flink.table.data.vector.heap.HeapBytesVector;
import org.apache.flink.table.data.vector.heap.HeapDoubleVector;
import org.apache.flink.table.data.vector.heap.HeapFloatVector;
import org.apache.flink.table.data.vector.heap.HeapIntVector;
import org.apache.flink.table.data.vector.heap.HeapLongVector;
import org.apache.flink.table.data.vector.heap.HeapShortVector;
import org.apache.flink.table.data.vector.heap.HeapTimestampVector;
import org.apache.flink.table.data.vector.writable.WritableColumnVector;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.table.types.logical.VarBinaryType;
import org.apache.flink.util.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.ParquetRuntimeException;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.column.page.PageReader;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.InvalidSchemaException;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.flink.table.runtime.functions.SqlDateTimeUtils.dateToInternal;
import static org.apache.parquet.Preconditions.checkArgument;

/**
 * Util for generating {@link ParquetColumnarRowSplitReader}.
 *
 * <p>NOTE: reference from Flink release 1.11.2 {@code ParquetSplitReaderUtil}, modify to support INT64
 * based TIMESTAMP_MILLIS as ConvertedType, should remove when Flink supports that.
 */
public class ParquetSplitReaderUtil {

  /**
   * Util for generating partitioned {@link ParquetColumnarRowSplitReader}.
   */
  public static ParquetColumnarRowSplitReader genPartColumnarRowReader(
      boolean utcTimestamp,
      boolean caseSensitive,
      Configuration conf,
      String[] fullFieldNames,
      DataType[] fullFieldTypes,
      Map<String, Object> partitionSpec,
      int[] selectedFields,
      int batchSize,
      Path path,
      long splitStart,
      long splitLength) throws IOException {
    List<String> selNonPartNames = Arrays.stream(selectedFields)
        .mapToObj(i -> fullFieldNames[i])
        .filter(n -> !partitionSpec.containsKey(n))
        .collect(Collectors.toList());

    int[] selParquetFields = Arrays.stream(selectedFields)
        .filter(i -> !partitionSpec.containsKey(fullFieldNames[i]))
        .toArray();

    ParquetColumnarRowSplitReader.ColumnBatchGenerator gen = readVectors -> {
      // create and initialize the row batch
      ColumnVector[] vectors = new ColumnVector[selectedFields.length];
      for (int i = 0; i < vectors.length; i++) {
        String name = fullFieldNames[selectedFields[i]];
        LogicalType type = fullFieldTypes[selectedFields[i]].getLogicalType();
        vectors[i] = createVector(readVectors, selNonPartNames, name, type, partitionSpec, batchSize);
      }
      return new VectorizedColumnBatch(vectors);
    };

    return new ParquetColumnarRowSplitReader(
        utcTimestamp,
        caseSensitive,
        conf,
        Arrays.stream(selParquetFields)
            .mapToObj(i -> fullFieldTypes[i].getLogicalType())
            .toArray(LogicalType[]::new),
        selNonPartNames.toArray(new String[0]),
        gen,
        batchSize,
        new org.apache.hadoop.fs.Path(path.toUri()),
        splitStart,
        splitLength);
  }

  private static ColumnVector createVector(
      ColumnVector[] readVectors,
      List<String> selNonPartNames,
      String name,
      LogicalType type,
      Map<String, Object> partitionSpec,
      int batchSize) {
    if (partitionSpec.containsKey(name)) {
      return createVectorFromConstant(type, partitionSpec.get(name), batchSize);
    }
    ColumnVector readVector = readVectors[selNonPartNames.indexOf(name)];
    if (readVector == null) {
      // when the read vector is null, use a constant null vector instead
      readVector = createVectorFromConstant(type, null, batchSize);
    }
    return readVector;
  }

  private static ColumnVector createVectorFromConstant(
      LogicalType type,
      Object value,
      int batchSize) {
    switch (type.getTypeRoot()) {
      case CHAR:
      case VARCHAR:
      case BINARY:
      case VARBINARY:
        HeapBytesVector bsv = new HeapBytesVector(batchSize);
        if (value == null) {
          bsv.fillWithNulls();
        } else {
          bsv.fill(value instanceof byte[]
              ? (byte[]) value
              : value.toString().getBytes(StandardCharsets.UTF_8));
        }
        return bsv;
      case BOOLEAN:
        HeapBooleanVector bv = new HeapBooleanVector(batchSize);
        if (value == null) {
          bv.fillWithNulls();
        } else {
          bv.fill((boolean) value);
        }
        return bv;
      case TINYINT:
        HeapByteVector byteVector = new HeapByteVector(batchSize);
        if (value == null) {
          byteVector.fillWithNulls();
        } else {
          byteVector.fill(((Number) value).byteValue());
        }
        return byteVector;
      case SMALLINT:
        HeapShortVector sv = new HeapShortVector(batchSize);
        if (value == null) {
          sv.fillWithNulls();
        } else {
          sv.fill(((Number) value).shortValue());
        }
        return sv;
      case INTEGER:
        HeapIntVector iv = new HeapIntVector(batchSize);
        if (value == null) {
          iv.fillWithNulls();
        } else {
          iv.fill(((Number) value).intValue());
        }
        return iv;
      case BIGINT:
        HeapLongVector lv = new HeapLongVector(batchSize);
        if (value == null) {
          lv.fillWithNulls();
        } else {
          lv.fill(((Number) value).longValue());
        }
        return lv;
      case DECIMAL:
        DecimalType decimalType = (DecimalType) type;
        int precision = decimalType.getPrecision();
        int scale = decimalType.getScale();
        DecimalData decimal = value == null
            ? null
            : Preconditions.checkNotNull(DecimalData.fromBigDecimal((BigDecimal) value, precision, scale));
        ColumnVector internalVector = createVectorFromConstant(
            new VarBinaryType(),
            decimal == null ? null : decimal.toUnscaledBytes(),
            batchSize);
        return new ParquetDecimalVector(internalVector);
      case FLOAT:
        HeapFloatVector fv = new HeapFloatVector(batchSize);
        if (value == null) {
          fv.fillWithNulls();
        } else {
          fv.fill(((Number) value).floatValue());
        }
        return fv;
      case DOUBLE:
        HeapDoubleVector dv = new HeapDoubleVector(batchSize);
        if (value == null) {
          dv.fillWithNulls();
        } else {
          dv.fill(((Number) value).doubleValue());
        }
        return dv;
      case DATE:
        if (value instanceof LocalDate) {
          value = Date.valueOf((LocalDate) value);
        }
        return createVectorFromConstant(
            new IntType(),
            value == null ? null : dateToInternal((Date) value),
            batchSize);
      case TIMESTAMP_WITHOUT_TIME_ZONE:
        HeapTimestampVector tv = new HeapTimestampVector(batchSize);
        if (value == null) {
          tv.fillWithNulls();
        } else {
          tv.fill(TimestampData.fromLocalDateTime((LocalDateTime) value));
        }
        return tv;
      default:
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }
  }

  private static List<ColumnDescriptor> filterDescriptors(int depth, Type type, List<ColumnDescriptor> columns) throws ParquetRuntimeException {
    List<ColumnDescriptor> filtered = new ArrayList<>();
    for (ColumnDescriptor descriptor : columns) {
      if (depth >= descriptor.getPath().length) {
        throw new InvalidSchemaException("Expect depth " + depth + " for schema: " + descriptor);
      }
      if (type.getName().equals(descriptor.getPath()[depth])) {
        filtered.add(descriptor);
      }
    }
    ValidationUtils.checkState(filtered.size() > 0, "Corrupted Parquet schema");
    return filtered;
  }

  public static ColumnReader createColumnReader(
      boolean utcTimestamp,
      LogicalType fieldType,
      Type physicalType,
      List<ColumnDescriptor> descriptors,
      PageReadStore pages) throws IOException {
    return createColumnReader(utcTimestamp, fieldType, physicalType, descriptors,
        pages, 0);
  }

  private static ColumnReader createColumnReader(
      boolean utcTimestamp,
      LogicalType fieldType,
      Type physicalType,
      List<ColumnDescriptor> columns,
      PageReadStore pages,
      int depth) throws IOException {
    List<ColumnDescriptor> descriptors = filterDescriptors(depth, physicalType, columns);
    ColumnDescriptor descriptor = descriptors.get(0);
    PageReader pageReader = pages.getPageReader(descriptor);
    switch (fieldType.getTypeRoot()) {
      case BOOLEAN:
        return new BooleanColumnReader(descriptor, pageReader);
      case TINYINT:
        return new ByteColumnReader(descriptor, pageReader);
      case DOUBLE:
        return new DoubleColumnReader(descriptor, pageReader);
      case FLOAT:
        return new FloatColumnReader(descriptor, pageReader);
      case INTEGER:
      case DATE:
      case TIME_WITHOUT_TIME_ZONE:
        return new IntColumnReader(descriptor, pageReader);
      case BIGINT:
        return new LongColumnReader(descriptor, pageReader);
      case SMALLINT:
        return new ShortColumnReader(descriptor, pageReader);
      case CHAR:
      case VARCHAR:
      case BINARY:
      case VARBINARY:
        return new BytesColumnReader(descriptor, pageReader);
      case TIMESTAMP_WITHOUT_TIME_ZONE:
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        switch (descriptor.getPrimitiveType().getPrimitiveTypeName()) {
          case INT64:
            return new Int64TimestampColumnReader(utcTimestamp, descriptor, pageReader, ((TimestampType)fieldType).getPrecision());
          case INT96:
            return new TimestampColumnReader(utcTimestamp, descriptor, pageReader);
          default:
            throw new AssertionError();
        }
      case DECIMAL:
        switch (descriptor.getPrimitiveType().getPrimitiveTypeName()) {
          case INT32:
            return new IntColumnReader(descriptor, pageReader);
          case INT64:
            return new LongColumnReader(descriptor, pageReader);
          case BINARY:
            return new BytesColumnReader(descriptor, pageReader);
          case FIXED_LEN_BYTE_ARRAY:
            return new FixedLenBytesColumnReader(
                descriptor, pageReader, ((DecimalType) fieldType).getPrecision());
          default:
            throw new AssertionError();
        }
      case ARRAY:
        return new ArrayColumnReader(
            descriptor,
            pageReader,
            utcTimestamp,
            descriptor.getPrimitiveType(),
            fieldType);
      case MAP:
        MapType mapType = (MapType) fieldType;
        ArrayColumnReader keyReader =
            new ArrayColumnReader(
                descriptor,
                pageReader,
                utcTimestamp,
                descriptor.getPrimitiveType(),
                new ArrayType(mapType.getKeyType()));
        ArrayColumnReader valueReader =
            new ArrayColumnReader(
                descriptors.get(1),
                pages.getPageReader(descriptors.get(1)),
                utcTimestamp,
                descriptors.get(1).getPrimitiveType(),
                new ArrayType(mapType.getValueType()));
        return new MapColumnReader(keyReader, valueReader, fieldType);
      case ROW:
        RowType rowType = (RowType) fieldType;
        GroupType groupType = physicalType.asGroupType();
        List<ColumnReader> fieldReaders = new ArrayList<>();
        for (int i = 0; i < rowType.getFieldCount(); i++) {
          fieldReaders.add(
              createColumnReader(
                  utcTimestamp,
                  rowType.getTypeAt(i),
                  groupType.getType(i),
                  descriptors,
                  pages,
                  depth + 1));
        }
        return new RowColumnReader(fieldReaders);
      default:
        throw new UnsupportedOperationException(fieldType + " is not supported now.");
    }
  }

  public static WritableColumnVector createWritableColumnVector(
      int batchSize,
      LogicalType fieldType,
      Type physicalType,
      List<ColumnDescriptor> descriptors) {
    return createWritableColumnVector(batchSize, fieldType, physicalType, descriptors, 0);
  }

  private static WritableColumnVector createWritableColumnVector(
      int batchSize,
      LogicalType fieldType,
      Type physicalType,
      List<ColumnDescriptor> columns,
      int depth) {
    List<ColumnDescriptor> descriptors = filterDescriptors(depth, physicalType, columns);
    PrimitiveType primitiveType = descriptors.get(0).getPrimitiveType();
    PrimitiveType.PrimitiveTypeName typeName = primitiveType.getPrimitiveTypeName();
    switch (fieldType.getTypeRoot()) {
      case BOOLEAN:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.BOOLEAN,
            "Unexpected type: %s", typeName);
        return new HeapBooleanVector(batchSize);
      case TINYINT:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.INT32,
            "Unexpected type: %s", typeName);
        return new HeapByteVector(batchSize);
      case DOUBLE:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.DOUBLE,
            "Unexpected type: %s", typeName);
        return new HeapDoubleVector(batchSize);
      case FLOAT:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.FLOAT,
            "Unexpected type: %s", typeName);
        return new HeapFloatVector(batchSize);
      case INTEGER:
      case DATE:
      case TIME_WITHOUT_TIME_ZONE:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.INT32,
            "Unexpected type: %s", typeName);
        return new HeapIntVector(batchSize);
      case BIGINT:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.INT64,
            "Unexpected type: %s", typeName);
        return new HeapLongVector(batchSize);
      case SMALLINT:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.INT32,
            "Unexpected type: %s", typeName);
        return new HeapShortVector(batchSize);
      case CHAR:
      case VARCHAR:
      case BINARY:
      case VARBINARY:
        checkArgument(
            typeName == PrimitiveType.PrimitiveTypeName.BINARY,
            "Unexpected type: %s", typeName);
        return new HeapBytesVector(batchSize);
      case TIMESTAMP_WITHOUT_TIME_ZONE:
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        checkArgument(primitiveType.getOriginalType() != OriginalType.TIME_MICROS,
            "TIME_MICROS original type is not ");
        return new HeapTimestampVector(batchSize);
      case DECIMAL:
        checkArgument(
            (typeName == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
                || typeName == PrimitiveType.PrimitiveTypeName.BINARY)
                && primitiveType.getOriginalType() == OriginalType.DECIMAL,
            "Unexpected type: %s", typeName);
        return new HeapBytesVector(batchSize);
      case ARRAY:
        ArrayType arrayType = (ArrayType) fieldType;
        return new HeapArrayVector(
            batchSize,
            createWritableColumnVector(
                batchSize,
                arrayType.getElementType(),
                physicalType,
                descriptors,
                depth));
      case MAP:
        MapType mapType = (MapType) fieldType;
        GroupType repeatedType = physicalType.asGroupType().getType(0).asGroupType();
        // the map column has three level paths.
        return new HeapMapColumnVector(
            batchSize,
            createWritableColumnVector(
                batchSize,
                mapType.getKeyType(),
                repeatedType.getType(0),
                descriptors,
                depth + 2),
            createWritableColumnVector(
                batchSize,
                mapType.getValueType(),
                repeatedType.getType(1),
                descriptors,
                depth + 2));
      case ROW:
        RowType rowType = (RowType) fieldType;
        GroupType groupType = physicalType.asGroupType();
        WritableColumnVector[] columnVectors =
            new WritableColumnVector[rowType.getFieldCount()];
        for (int i = 0; i < columnVectors.length; i++) {
          columnVectors[i] =
              createWritableColumnVector(
                  batchSize,
                  rowType.getTypeAt(i),
                  groupType.getType(i),
                  descriptors,
                  depth + 1);
        }
        return new HeapRowColumnVector(batchSize, columnVectors);
      default:
        throw new UnsupportedOperationException(fieldType + " is not supported now.");
    }
  }
}
