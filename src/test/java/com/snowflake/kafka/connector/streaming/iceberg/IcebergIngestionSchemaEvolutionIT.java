package com.snowflake.kafka.connector.streaming.iceberg;

import static com.snowflake.kafka.connector.streaming.iceberg.TestJsons.*;
import static com.snowflake.kafka.connector.streaming.iceberg.sql.ComplexJsonRecord.*;
import static com.snowflake.kafka.connector.streaming.iceberg.sql.PrimitiveJsonRecord.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.internal.DescribeTableRow;
import com.snowflake.kafka.connector.streaming.iceberg.sql.MetadataRecord;
import com.snowflake.kafka.connector.streaming.iceberg.sql.MetadataRecord.RecordWithMetadata;
import com.snowflake.kafka.connector.streaming.iceberg.sql.PrimitiveJsonRecord;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class IcebergIngestionSchemaEvolutionIT extends IcebergIngestionIT {

  private static final String RECORD_METADATA_TYPE =
      "OBJECT(offset NUMBER(10,0), topic VARCHAR(16777216), partition NUMBER(10,0), key"
          + " VARCHAR(16777216), schema_id NUMBER(10,0), key_schema_id NUMBER(10,0),"
          + " CreateTime NUMBER(19,0), LogAppendTime NUMBER(19,0),"
          + " SnowflakeConnectorPushTime NUMBER(19,0), headers MAP(VARCHAR(16777216),"
          + " VARCHAR(16777216)))";

  @Override
  protected Boolean isSchemaEvolutionEnabled() {
    return true;
  }

  @Override
  protected void createIcebergTable() {
    createIcebergTable(tableName);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prepareData")
  @Disabled
  void shouldEvolveSchemaAndInsertRecords(
      String description, String message, DescribeTableRow[] expectedSchema, boolean withSchema)
      throws Exception {
    // start off with just one column
    List<DescribeTableRow> rows = describeTable(tableName);
    assertThat(rows)
        .hasSize(1)
        .extracting(DescribeTableRow::getColumn)
        .contains(Utils.TABLE_COLUMN_METADATA);

    SinkRecord record = createKafkaRecord(message, 0, withSchema);
    service.insert(Collections.singletonList(record));
    waitForOffset(-1);
    rows = describeTable(tableName);
    assertThat(rows.size()).isEqualTo(9);

    // don't check metadata column schema, we have different tests for that
    rows =
        rows.stream()
            .filter(r -> !r.getColumn().equals(Utils.TABLE_COLUMN_METADATA))
            .collect(Collectors.toList());

    assertThat(rows).containsExactlyInAnyOrder(expectedSchema);

    // resend and store same record without any issues now
    service.insert(Collections.singletonList(record));
    waitForOffset(1);

    // and another record with same schema
    service.insert(Collections.singletonList(createKafkaRecord(message, 1, withSchema)));
    waitForOffset(2);

    // and another record with extra field - schema evolves again
    service.insert(Collections.singletonList(createKafkaRecord(simpleRecordJson, 2, false)));

    rows = describeTable(tableName);
    assertThat(rows).hasSize(10).contains(new DescribeTableRow("SIMPLE", "VARCHAR(16777216)"));

    // reinsert record with extra field
    service.insert(Collections.singletonList(createKafkaRecord(simpleRecordJson, 2, false)));
    waitForOffset(3);

    assertRecordsInTable();
  }

  private static Stream<Arguments> prepareData() {
    return Stream.of(
        Arguments.of(
            "Primitive JSON with schema",
            primitiveJsonWithSchemaExample,
            new DescribeTableRow[] {
              new DescribeTableRow("ID_INT8", "NUMBER(10,0)"),
              new DescribeTableRow("ID_INT16", "NUMBER(10,0)"),
              new DescribeTableRow("ID_INT32", "NUMBER(10,0)"),
              new DescribeTableRow("ID_INT64", "NUMBER(19,0)"),
              new DescribeTableRow("DESCRIPTION", "VARCHAR(16777216)"),
              new DescribeTableRow("RATING_FLOAT32", "FLOAT"),
              new DescribeTableRow("RATING_FLOAT64", "FLOAT"),
              new DescribeTableRow("APPROVAL", "BOOLEAN")
            },
            true),
        Arguments.of(
            "Primitive JSON without schema",
            primitiveJsonExample,
            new DescribeTableRow[] {
              new DescribeTableRow("ID_INT8", "NUMBER(19,0)"),
              new DescribeTableRow("ID_INT16", "NUMBER(19,0)"),
              new DescribeTableRow("ID_INT32", "NUMBER(19,0)"),
              new DescribeTableRow("ID_INT64", "NUMBER(19,0)"),
              new DescribeTableRow("DESCRIPTION", "VARCHAR(16777216)"),
              new DescribeTableRow("RATING_FLOAT32", "FLOAT"),
              new DescribeTableRow("RATING_FLOAT64", "FLOAT"),
              new DescribeTableRow("APPROVAL", "BOOLEAN")
            },
            false));
  }

  /** Verify a scenario when structure is enriched with another field. */
  @Test
  @Disabled
  public void alterStructure_noSchema() throws Exception {
    // k1, k2
    String testStruct1 = "{ \"testStruct\": { \"k1\" : 1, \"k2\" : 2 } }";
    insertWithRetry(testStruct1, 0, false);
    waitForOffset(1);

    // k1, k2 + k3
    String testStruct2 = "{ \"testStruct\": { \"k1\" : 1, \"k2\" : 2, \"k3\" : \"foo\" } }";
    insertWithRetry(testStruct2, 1, false);
    waitForOffset(2);

    // k1, k2, k3 + k4
    String testStruct3 =
        "{ \"testStruct\": { \"k1\" : 1, \"k2\" : 2, \"k3\" : \"bar\", \"k4\" : 4.5 } }";
    insertWithRetry(testStruct3, 2, false);
    waitForOffset(3);

    List<DescribeTableRow> columns = describeTable(tableName);
    assertEquals(
        columns.get(1).getType(),
        "OBJECT(k1 NUMBER(19,0), k2 NUMBER(19,0), k3 VARCHAR(16777216), k4 FLOAT)");

    // k2, k3, k4
    String testStruct4 = "{ \"testStruct\": { \"k2\" : 2, \"k3\" : 3, \"k4\" : 4.34 } }";
    insertWithRetry(testStruct4, 3, false);
    waitForOffset(4);

    columns = describeTable(tableName);
    assertEquals(
        columns.get(1).getType(),
        "OBJECT(k1 NUMBER(19,0), k2 NUMBER(19,0), k3 VARCHAR(16777216), k4 FLOAT)");

    // k5, k6
    String testStruct5 = "{ \"testStruct\": { \"k5\" : 2, \"k6\" : 3 } }";
    insertWithRetry(testStruct5, 4, false);
    waitForOffset(5);

    columns = describeTable(tableName);
    assertEquals(
        columns.get(1).getType(),
        "OBJECT(k1 NUMBER(19,0), k2 NUMBER(19,0), k3 VARCHAR(16777216), k4 FLOAT, k5 NUMBER(19,0),"
            + " k6 NUMBER(19,0))");
    assertEquals(columns.size(), 2);
  }

  private void insertWithRetry(String record, int offset, boolean withSchema) {
    service.insert(Collections.singletonList(createKafkaRecord(record, offset, withSchema)));
    service.insert(Collections.singletonList(createKafkaRecord(record, offset, withSchema)));
  }

  private void assertRecordsInTable() {
    List<RecordWithMetadata<PrimitiveJsonRecord>> recordsWithMetadata =
        selectAllSchematizedRecords();

    assertThat(recordsWithMetadata)
        .hasSize(3)
        .extracting(RecordWithMetadata::getRecord)
        .containsExactly(
            primitiveJsonRecordValueExample,
            primitiveJsonRecordValueExample,
            emptyPrimitiveJsonRecordValueExample);
    List<MetadataRecord> metadataRecords =
        recordsWithMetadata.stream()
            .map(RecordWithMetadata::getMetadata)
            .collect(Collectors.toList());
    assertThat(metadataRecords).extracting(MetadataRecord::getOffset).containsExactly(0L, 1L, 2L);
    assertThat(metadataRecords)
        .hasSize(3)
        .allMatch(
            r ->
                r.getTopic().equals(topicPartition.topic())
                    && r.getPartition().equals(topicPartition.partition())
                    && r.getKey().equals("test")
                    && r.getSnowflakeConnectorPushTime() != null);
  }

  @Test
  @Disabled
  public void testComplexRecordEvolution_withSchema() throws Exception {
    insertWithRetry(complexJsonWithSchemaExample, 0, true);
    waitForOffset(1);

    List<DescribeTableRow> columns = describeTable(tableName);
    assertEquals(columns.size(), 16);

    DescribeTableRow[] expectedSchema =
        new DescribeTableRow[] {
          new DescribeTableRow("RECORD_METADATA", RECORD_METADATA_TYPE),
          new DescribeTableRow("ID_INT8", "NUMBER(10,0)"),
          new DescribeTableRow("ID_INT16", "NUMBER(10,0)"),
          new DescribeTableRow("ID_INT32", "NUMBER(10,0)"),
          new DescribeTableRow("ID_INT64", "NUMBER(19,0)"),
          new DescribeTableRow("DESCRIPTION", "VARCHAR(16777216)"),
          new DescribeTableRow("RATING_FLOAT32", "FLOAT"),
          new DescribeTableRow("RATING_FLOAT64", "FLOAT"),
          new DescribeTableRow("APPROVAL", "BOOLEAN"),
          new DescribeTableRow("ARRAY1", "ARRAY(NUMBER(10,0))"),
          new DescribeTableRow("ARRAY2", "ARRAY(VARCHAR(16777216))"),
          new DescribeTableRow("ARRAY3", "ARRAY(BOOLEAN)"),
          new DescribeTableRow("ARRAY4", "ARRAY(NUMBER(10,0))"),
          new DescribeTableRow("ARRAY5", "ARRAY(ARRAY(NUMBER(10,0)))"),
          new DescribeTableRow(
              "NESTEDRECORD",
              "OBJECT(id_int8 NUMBER(10,0), id_int16 NUMBER(10,0), id_int32 NUMBER(10,0), id_int64"
                  + " NUMBER(19,0), description VARCHAR(16777216), rating_float32 FLOAT,"
                  + " rating_float64 FLOAT, approval BOOLEAN)"),
          new DescribeTableRow(
              "NESTEDRECORD2",
              "OBJECT(id_int8 NUMBER(10,0), id_int16 NUMBER(10,0), id_int32 NUMBER(10,0), id_int64"
                  + " NUMBER(19,0), description VARCHAR(16777216), rating_float32 FLOAT,"
                  + " rating_float64 FLOAT, approval BOOLEAN)"),
        };
    assertThat(columns).containsExactlyInAnyOrder(expectedSchema);
  }

  @Test
  @Disabled
  public void testComplexRecordEvolution() throws Exception {
    insertWithRetry(complexJsonPayloadExample, 0, false);
    waitForOffset(1);

    List<DescribeTableRow> columns = describeTable(tableName);
    assertEquals(columns.size(), 16);

    DescribeTableRow[] expectedSchema =
        new DescribeTableRow[] {
          new DescribeTableRow("RECORD_METADATA", RECORD_METADATA_TYPE),
          new DescribeTableRow("ID_INT8", "NUMBER(19,0)"),
          new DescribeTableRow("ID_INT16", "NUMBER(19,0)"),
          new DescribeTableRow("ID_INT32", "NUMBER(19,0)"),
          new DescribeTableRow("ID_INT64", "NUMBER(19,0)"),
          new DescribeTableRow("DESCRIPTION", "VARCHAR(16777216)"),
          new DescribeTableRow("RATING_FLOAT32", "FLOAT"),
          new DescribeTableRow("RATING_FLOAT64", "FLOAT"),
          new DescribeTableRow("APPROVAL", "BOOLEAN"),
          new DescribeTableRow("ARRAY1", "ARRAY(NUMBER(19,0))"),
          new DescribeTableRow("ARRAY2", "ARRAY(VARCHAR(16777216))"),
          new DescribeTableRow("ARRAY3", "ARRAY(BOOLEAN)"),
          // "array4" : null -> VARCHAR(16777216
          new DescribeTableRow("ARRAY4", "VARCHAR(16777216)"),
          new DescribeTableRow("ARRAY5", "ARRAY(ARRAY(NUMBER(19,0)))"),
          new DescribeTableRow(
              "NESTEDRECORD",
              "OBJECT(id_int8 NUMBER(19,0), id_int16 NUMBER(19,0), rating_float32 FLOAT,"
                  + " rating_float64 FLOAT, approval BOOLEAN, id_int32 NUMBER(19,0), description"
                  + " VARCHAR(16777216), id_int64 NUMBER(19,0))"),
          // "nestedRecord2": null -> VARCHAR(16777216)
          new DescribeTableRow("NESTEDRECORD2", "VARCHAR(16777216)"),
        };
    assertThat(columns).containsExactlyInAnyOrder(expectedSchema);
  }

  /** Test just for a scenario when we see a record for the first time. */
  @ParameterizedTest
  @MethodSource("schemasAndPayloads_brandNewColumns")
  @Disabled
  public void addBrandNewColumns_withSchema(
      String payloadWithSchema, String expectedColumnName, String expectedType) throws Exception {
    // when
    insertWithRetry(payloadWithSchema, 0, true);
    waitForOffset(1);
    // then
    List<DescribeTableRow> columns = describeTable(tableName);

    assertEquals(2, columns.size());
    assertEquals(expectedColumnName, columns.get(1).getColumn());
    assertEquals(expectedType, columns.get(1).getType());
  }

  private static Stream<Arguments> schemasAndPayloads_brandNewColumns() {
    return Stream.of(
        Arguments.of(
            nestedObjectWithSchema(),
            "OBJECT_WITH_NESTED_OBJECTS",
            "OBJECT(nestedStruct OBJECT(description VARCHAR(16777216)))"),
        Arguments.of(
            simpleMapWithSchema(), "SIMPLE_TEST_MAP", "MAP(VARCHAR(16777216), NUMBER(10,0))"),
        Arguments.of(simpleArrayWithSchema(), "SIMPLE_ARRAY", "ARRAY(NUMBER(10,0))"),
        Arguments.of(
            complexPayloadWithSchema(),
            "OBJECT",
            "OBJECT(arrayOfMaps ARRAY(MAP(VARCHAR(16777216), FLOAT)))"));
  }

  @ParameterizedTest
  @MethodSource("primitiveEvolutionDataSource")
  @Disabled
  public void testEvolutionOfPrimitives_withSchema(
      String singleBooleanField,
      String booleanAndInt,
      String booleanAndAllKindsOfInt,
      String allPrimitives,
      boolean withSchema)
      throws Exception {
    // when insert BOOLEAN
    insertWithRetry(singleBooleanField, 0, withSchema);
    waitForOffset(1);
    List<DescribeTableRow> columns = describeTable(tableName);
    // verify number of columns, datatype and column name
    assertEquals(2, columns.size());
    assertEquals("TEST_BOOLEAN", columns.get(1).getColumn());
    assertEquals("BOOLEAN", columns.get(1).getType());

    // evolve the schema BOOLEAN, INT64
    insertWithRetry(booleanAndInt, 1, withSchema);
    waitForOffset(2);
    columns = describeTable(tableName);
    assertEquals(3, columns.size());
    // verify data types in already existing column were not changed
    assertEquals("TEST_BOOLEAN", columns.get(1).getColumn());
    assertEquals("BOOLEAN", columns.get(1).getType());
    // verify new columns
    assertEquals("TEST_INT64", columns.get(2).getColumn());
    assertEquals("NUMBER(19,0)", columns.get(2).getType());

    // evolve the schema BOOLEAN, INT64, INT32, INT16, INT8,
    insertWithRetry(booleanAndAllKindsOfInt, 2, withSchema);
    waitForOffset(3);
    columns = describeTable(tableName);
    assertEquals(6, columns.size());
    // verify data types in already existing column were not changed

    // without schema every number is parsed to NUMBER(19,0)
    String SMALL_INT = withSchema ? "NUMBER(10,0)" : "NUMBER(19,0)";
    DescribeTableRow[] expectedSchema =
        new DescribeTableRow[] {
          new DescribeTableRow("RECORD_METADATA", RECORD_METADATA_TYPE),
          new DescribeTableRow("TEST_BOOLEAN", "BOOLEAN"),
          new DescribeTableRow("TEST_INT8", SMALL_INT),
          new DescribeTableRow("TEST_INT16", SMALL_INT),
          new DescribeTableRow("TEST_INT32", SMALL_INT),
          new DescribeTableRow("TEST_INT64", "NUMBER(19,0)")
        };
    assertThat(columns).containsExactlyInAnyOrder(expectedSchema);

    // evolve the schema BOOLEAN, INT64, INT32, INT16, INT8, FLOAT, DOUBLE, STRING
    insertWithRetry(allPrimitives, 3, withSchema);
    waitForOffset(4);
    columns = describeTable(tableName);
    assertEquals(9, columns.size());

    expectedSchema =
        new DescribeTableRow[] {
          new DescribeTableRow("RECORD_METADATA", RECORD_METADATA_TYPE),
          new DescribeTableRow("TEST_BOOLEAN", "BOOLEAN"),
          new DescribeTableRow("TEST_INT8", SMALL_INT),
          new DescribeTableRow("TEST_INT16", SMALL_INT),
          new DescribeTableRow("TEST_INT32", SMALL_INT),
          new DescribeTableRow("TEST_INT64", "NUMBER(19,0)"),
          new DescribeTableRow("TEST_STRING", "VARCHAR(16777216)"),
          new DescribeTableRow("TEST_FLOAT", "FLOAT"),
          new DescribeTableRow("TEST_DOUBLE", "FLOAT")
        };

    assertThat(columns).containsExactlyInAnyOrder(expectedSchema);
  }

  private static Stream<Arguments> primitiveEvolutionDataSource() {
    return Stream.of(
        Arguments.of(
            singleBooleanField(),
            booleanAndIntWithSchema(),
            booleanAndAllKindsOfIntWithSchema(),
            allPrimitivesWithSchema(),
            true),
        Arguments.of(
            singleBooleanFieldPayload(),
            booleanAndIntPayload(),
            booleanAndAllKindsOfIntPayload(),
            allPrimitivesPayload(),
            false));
  }

  @ParameterizedTest
  @MethodSource("testEvolutionOfComplexTypes_dataSource")
  @Disabled
  public void testEvolutionOfComplexTypes_withSchema(
      String objectVarchar,
      String objectWithNestedObject,
      String twoObjects,
      String twoObjectsExtendedWithMapAndArray,
      boolean withSchema)
      throws Exception {
    // insert
    insertWithRetry(objectVarchar, 0, withSchema);
    waitForOffset(1);
    List<DescribeTableRow> columns = describeTable(tableName);
    // verify number of columns, datatype and column name
    assertEquals(2, columns.size());
    assertEquals("OBJECT", columns.get(1).getColumn());
    assertEquals("OBJECT(test_string VARCHAR(16777216))", columns.get(1).getType());

    // evolution
    insertWithRetry(objectWithNestedObject, 1, withSchema);
    waitForOffset(2);
    columns = describeTable(tableName);
    // verify number of columns, datatype and column name
    assertEquals(2, columns.size());
    assertEquals("OBJECT", columns.get(1).getColumn());
    assertEquals(
        "OBJECT(test_string VARCHAR(16777216), nested_object OBJECT(test_string"
            + " VARCHAR(16777216)))",
        columns.get(1).getType());

    // evolution
    insertWithRetry(twoObjects, 2, withSchema);
    waitForOffset(3);
    columns = describeTable(tableName);

    assertEquals(3, columns.size());
    // 1st column
    assertEquals("OBJECT", columns.get(1).getColumn());
    assertEquals(
        "OBJECT(test_string VARCHAR(16777216), nested_object OBJECT(test_string"
            + " VARCHAR(16777216)))",
        columns.get(1).getType());
    // 2nd column
    assertEquals("OBJECT_WITH_NESTED_OBJECTS", columns.get(2).getColumn());
    assertEquals(
        "OBJECT(nestedStruct OBJECT(description VARCHAR(16777216)))", columns.get(2).getType());

    // evolution
    insertWithRetry(twoObjectsExtendedWithMapAndArray, 3, withSchema);
    waitForOffset(4);
    columns = describeTable(tableName);

    assertEquals(3, columns.size());
    // 1st column
    assertEquals("OBJECT", columns.get(1).getColumn());
    if (withSchema) {
      // MAP is not supported without schema, execute this assertion only when there is a schema,
      assertEquals(
          "OBJECT(test_string VARCHAR(16777216), nested_object OBJECT(test_string"
              + " VARCHAR(16777216)), Test_Map MAP(VARCHAR(16777216), OBJECT(test_string"
              + " VARCHAR(16777216))))",
          columns.get(1).getType());
    }
    // 2nd column
    assertEquals("OBJECT_WITH_NESTED_OBJECTS", columns.get(2).getColumn());
    assertEquals(
        "OBJECT(nestedStruct OBJECT(description VARCHAR(16777216), test_array ARRAY(FLOAT)))",
        columns.get(2).getType());
  }

  private static Stream<Arguments> testEvolutionOfComplexTypes_dataSource() {
    return Stream.of(
        Arguments.of(
            objectVarcharWithSchema(),
            objectWithNestedObjectWithSchema(),
            twoObjectsWithSchema(),
            twoObjectsExtendedWithMapAndArrayWithSchema(),
            true),
        Arguments.of(
            objectVarcharPayload,
            objectWithNestedObjectPayload(),
            twoObjectsWithSchemaPayload(),
            twoObjectsExtendedWithMapAndArrayPayload(),
            false));
  }
}
