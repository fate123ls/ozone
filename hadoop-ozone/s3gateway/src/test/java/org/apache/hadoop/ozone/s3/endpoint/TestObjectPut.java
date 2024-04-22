/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.hadoop.ozone.s3.endpoint;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientStub;
import org.apache.hadoop.ozone.client.OzoneKeyDetails;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.protocol.ClientProtocol;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;
import org.apache.hadoop.ozone.s3.exception.S3ErrorTable;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.ozone.s3.util.S3Consts.CUSTOM_METADATA_COPY_DIRECTIVE_HEADER;
import static org.apache.hadoop.ozone.s3.util.S3Consts.CUSTOM_METADATA_HEADER_PREFIX;
import static org.apache.hadoop.ozone.s3.util.S3Consts.DECODED_CONTENT_LENGTH_HEADER;
import static org.apache.hadoop.ozone.s3.util.S3Consts.COPY_SOURCE_HEADER;
import static org.apache.hadoop.ozone.s3.util.S3Consts.STORAGE_CLASS_HEADER;
import static org.apache.hadoop.ozone.s3.util.S3Utils.urlEncode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test put object.
 */
public class TestObjectPut {
  public static final String CONTENT = "0123456789";
  private String bucketName = "b1";
  private String keyName = "key=value/1";
  private String destBucket = "b2";
  private String destkey = "key=value/2";
  private String nonexist = "nonexist";
  private OzoneClient clientStub;
  private ObjectEndpoint objectEndpoint;

  @BeforeEach
  public void setup() throws IOException {
    //Create client stub and object store stub.
    clientStub = new OzoneClientStub();

    // Create bucket
    clientStub.getObjectStore().createS3Bucket(bucketName);
    clientStub.getObjectStore().createS3Bucket(destBucket);

    // Create PutObject and setClient to OzoneClientStub
    objectEndpoint = spy(new ObjectEndpoint());
    objectEndpoint.setClient(clientStub);
    objectEndpoint.setOzoneConfiguration(new OzoneConfiguration());
  }

  @Test
  public void testPutObject() throws IOException, OS3Exception {
    //GIVEN
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);

    //WHEN
    Response response = objectEndpoint.put(bucketName, keyName, CONTENT
        .length(), 1, null, body);


    //THEN
    OzoneInputStream ozoneInputStream =
        clientStub.getObjectStore().getS3Bucket(bucketName)
            .readKey(keyName);
    String keyContent =
        IOUtils.toString(ozoneInputStream, UTF_8);
    OzoneKeyDetails keyDetails = clientStub.getObjectStore().getS3Bucket(bucketName).getKey(keyName);

    assertEquals(200, response.getStatus());
    assertEquals(CONTENT, keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertTrue(StringUtils.isNotEmpty(keyDetails.getMetadata().get(OzoneConsts.ETAG)));
  }

  @Test
  public void testPutObjectWithECReplicationConfig()
      throws IOException, OS3Exception {
    //GIVEN
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);
    ECReplicationConfig ecReplicationConfig =
        new ECReplicationConfig("rs-3-2-1024K");
    clientStub.getObjectStore().getS3Bucket(bucketName)
        .setReplicationConfig(ecReplicationConfig);
    Response response = objectEndpoint.put(bucketName, keyName, CONTENT
        .length(), 1, null, body);

    assertEquals(ecReplicationConfig,
        clientStub.getObjectStore().getS3Bucket(bucketName).getKey(keyName)
            .getReplicationConfig());
    OzoneInputStream ozoneInputStream =
        clientStub.getObjectStore().getS3Bucket(bucketName)
            .readKey(keyName);
    String keyContent =
        IOUtils.toString(ozoneInputStream, UTF_8);
    OzoneKeyDetails keyDetails = clientStub.getObjectStore().getS3Bucket(bucketName).getKey(keyName);

    assertEquals(200, response.getStatus());
    assertEquals(CONTENT, keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertTrue(StringUtils.isNotEmpty(keyDetails.getMetadata().get(OzoneConsts.ETAG)));
  }

  @Test
  public void testPutObjectContentLength() throws IOException, OS3Exception {
    // The contentLength specified when creating the Key should be the same as
    // the Content-Length, the key Commit will compare the Content-Length with
    // the actual length of the data written.
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);
    long dataSize = CONTENT.length();

    objectEndpoint.put(bucketName, keyName, dataSize, 0, null, body);
    assertEquals(dataSize, getKeyDataSize(keyName));
  }

  @Test
  public void testPutObjectContentLengthForStreaming()
      throws IOException, OS3Exception {
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    objectEndpoint.setHeaders(headers);

    String chunkedContent = "0a;chunk-signature=signature\r\n"
        + "1234567890\r\n"
        + "05;chunk-signature=signature\r\n"
        + "abcde\r\n";

    when(headers.getHeaderString("x-amz-content-sha256"))
        .thenReturn("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");

    when(headers.getHeaderString(DECODED_CONTENT_LENGTH_HEADER))
        .thenReturn("15");
    objectEndpoint.put(bucketName, keyName, chunkedContent.length(), 0, null,
        new ByteArrayInputStream(chunkedContent.getBytes(UTF_8)));
    assertEquals(15, getKeyDataSize(keyName));
  }

  private long getKeyDataSize(String key) throws IOException {
    return clientStub.getObjectStore().getS3Bucket(bucketName)
        .getKey(key).getDataSize();
  }

  @Test
  public void testPutObjectWithSignedChunks() throws IOException, OS3Exception {
    //GIVEN
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    objectEndpoint.setHeaders(headers);

    String chunkedContent = "0a;chunk-signature=signature\r\n"
        + "1234567890\r\n"
        + "05;chunk-signature=signature\r\n"
        + "abcde\r\n";

    when(headers.getHeaderString("x-amz-content-sha256"))
        .thenReturn("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
    when(headers.getHeaderString(DECODED_CONTENT_LENGTH_HEADER))
        .thenReturn("15");

    //WHEN
    Response response = objectEndpoint.put(bucketName, keyName,
        chunkedContent.length(), 1, null,
        new ByteArrayInputStream(chunkedContent.getBytes(UTF_8)));

    //THEN
    OzoneInputStream ozoneInputStream =
        clientStub.getObjectStore().getS3Bucket(bucketName)
            .readKey(keyName);
    String keyContent = IOUtils.toString(ozoneInputStream, UTF_8);
    OzoneKeyDetails keyDetails = clientStub.getObjectStore().getS3Bucket(bucketName).getKey(keyName);

    assertEquals(200, response.getStatus());
    assertEquals("1234567890abcde", keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertTrue(StringUtils.isNotEmpty(keyDetails.getMetadata().get(OzoneConsts.ETAG)));
  }

  @Test
  public void testPutObjectMessageDigestResetDuringException() throws OS3Exception {
    MessageDigest messageDigest = mock(MessageDigest.class);
    try (MockedStatic<IOUtils> mocked = mockStatic(IOUtils.class)) {
      // For example, EOFException during put-object due to client cancelling the operation before it completes
      mocked.when(() -> IOUtils.copyLarge(any(InputStream.class), any(OutputStream.class)))
          .thenThrow(IOException.class);
      when(objectEndpoint.getMessageDigestInstance()).thenReturn(messageDigest);

      HttpHeaders headers = mock(HttpHeaders.class);
      ByteArrayInputStream body =
          new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
      objectEndpoint.setHeaders(headers);
      try {
        objectEndpoint.put(bucketName, keyName, CONTENT
            .length(), 1, null, body);
        fail("Should throw IOException");
      } catch (IOException ignored) {
        // Verify that the message digest is reset so that the instance can be reused for the
        // next request in the same thread
        verify(messageDigest, times(1)).reset();
      }
    }
  }

  @Test
  public void testCopyObject() throws IOException, OS3Exception {
    // Put object in to source bucket
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);
    keyName = "sourceKey";

    // Add some custom metadata
    MultivaluedMap<String, String> metadataHeaders = new MultivaluedHashMap<>();
    metadataHeaders.putSingle(CUSTOM_METADATA_HEADER_PREFIX + "custom-key-1", "custom-value-1");
    metadataHeaders.putSingle(CUSTOM_METADATA_HEADER_PREFIX + "custom-key-2", "custom-value-2");
    when(headers.getRequestHeaders()).thenReturn(metadataHeaders);
    // Add COPY metadata directive (default)
    when(headers.getHeaderString(CUSTOM_METADATA_COPY_DIRECTIVE_HEADER)).thenReturn("COPY");

    Response response = objectEndpoint.put(bucketName, keyName,
        CONTENT.length(), 1, null, body);

    OzoneInputStream ozoneInputStream = clientStub.getObjectStore()
        .getS3Bucket(bucketName)
        .readKey(keyName);

    String keyContent = IOUtils.toString(ozoneInputStream, UTF_8);
    OzoneKeyDetails keyDetails = clientStub.getObjectStore().getS3Bucket(bucketName).getKey(keyName);

    assertEquals(200, response.getStatus());
    assertEquals(CONTENT, keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertTrue(StringUtils.isNotEmpty(keyDetails.getMetadata().get(OzoneConsts.ETAG)));
    assertEquals("custom-value-1", keyDetails.getMetadata().get("custom-key-1"));
    assertEquals("custom-value-2", keyDetails.getMetadata().get("custom-key-2"));

    String sourceETag = keyDetails.getMetadata().get(OzoneConsts.ETAG);

    // This will be ignored since the copy directive is COPY
    metadataHeaders.putSingle(CUSTOM_METADATA_HEADER_PREFIX + "custom-key-3", "custom-value-3");

    // Add copy header, and then call put
    when(headers.getHeaderString(COPY_SOURCE_HEADER)).thenReturn(
        bucketName  + "/" + urlEncode(keyName));

    response = objectEndpoint.put(destBucket, destkey, CONTENT.length(), 1,
        null, body);

    // Check destination key and response
    ozoneInputStream = clientStub.getObjectStore().getS3Bucket(destBucket)
        .readKey(destkey);

    keyContent = IOUtils.toString(ozoneInputStream, UTF_8);
    OzoneKeyDetails sourceKeyDetails = clientStub.getObjectStore()
        .getS3Bucket(bucketName).getKey(keyName);
    OzoneKeyDetails destKeyDetails = clientStub.getObjectStore()
        .getS3Bucket(destBucket).getKey(destkey);

    assertEquals(200, response.getStatus());
    assertEquals(CONTENT, keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertTrue(StringUtils.isNotEmpty(keyDetails.getMetadata().get(OzoneConsts.ETAG)));
    // Source key eTag should remain unchanged and the dest key should have
    // the same Etag since the key content is the same
    assertEquals(sourceETag, sourceKeyDetails.getMetadata().get(OzoneConsts.ETAG));
    assertEquals(sourceETag, destKeyDetails.getMetadata().get(OzoneConsts.ETAG));
    assertEquals("custom-value-1", destKeyDetails.getMetadata().get("custom-key-1"));
    assertEquals("custom-value-2", destKeyDetails.getMetadata().get("custom-key-2"));
    assertFalse(destKeyDetails.getMetadata().containsKey("custom-key-3"));

    // Now use REPLACE metadata directive (default) and remove some custom metadata used in the source key
    when(headers.getHeaderString(CUSTOM_METADATA_COPY_DIRECTIVE_HEADER)).thenReturn("REPLACE");
    metadataHeaders.remove(CUSTOM_METADATA_HEADER_PREFIX + "custom-key-1");
    metadataHeaders.remove(CUSTOM_METADATA_HEADER_PREFIX + "custom-key-2");

    response = objectEndpoint.put(destBucket, destkey, CONTENT.length(), 1,
        null, body);

    ozoneInputStream = clientStub.getObjectStore().getS3Bucket(destBucket)
        .readKey(destkey);

    keyContent = IOUtils.toString(ozoneInputStream, UTF_8);
    sourceKeyDetails = clientStub.getObjectStore()
        .getS3Bucket(bucketName).getKey(keyName);
    destKeyDetails = clientStub.getObjectStore()
        .getS3Bucket(destBucket).getKey(destkey);

    assertEquals(200, response.getStatus());
    assertEquals(CONTENT, keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertFalse(keyDetails.getMetadata().get(OzoneConsts.ETAG).isEmpty());
    // Source key eTag should remain unchanged and the dest key should have
    // the same Etag since the key content is the same
    assertEquals(sourceETag, sourceKeyDetails.getMetadata().get(OzoneConsts.ETAG));
    assertEquals(sourceETag, destKeyDetails.getMetadata().get(OzoneConsts.ETAG));
    assertFalse(destKeyDetails.getMetadata().containsKey("custom-key-1"));
    assertFalse(destKeyDetails.getMetadata().containsKey("custom-key-2"));
    assertEquals("custom-value-3", destKeyDetails.getMetadata().get("custom-key-3"));


    // wrong copy metadata directive
    when(headers.getHeaderString(CUSTOM_METADATA_COPY_DIRECTIVE_HEADER)).thenReturn("INVALID");
    OS3Exception e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(
        destBucket, destkey, CONTENT.length(), 1, null, body),
        "test copy object failed");
    assertEquals(400, e.getHttpCode());
    assertEquals("InvalidArgument", e.getCode());
    assertTrue(e.getErrorMessage().contains("The metadata directive specified is invalid"));

    when(headers.getHeaderString(CUSTOM_METADATA_COPY_DIRECTIVE_HEADER)).thenReturn("COPY");

    // source and dest same
    e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(
            bucketName, keyName, CONTENT.length(), 1, null, body),
        "test copy object failed");
    assertTrue(e.getErrorMessage().contains("This copy request is illegal"));

    // source bucket not found
    when(headers.getHeaderString(COPY_SOURCE_HEADER)).thenReturn(
        nonexist + "/"  + urlEncode(keyName));
    e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(destBucket,
        destkey, CONTENT.length(), 1, null, body), "test copy object failed");
    assertTrue(e.getCode().contains("NoSuchBucket"));

    // dest bucket not found
    when(headers.getHeaderString(COPY_SOURCE_HEADER)).thenReturn(
        bucketName + "/" + urlEncode(keyName));
    e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(nonexist,
        destkey, CONTENT.length(), 1, null, body), "test copy object failed");
    assertTrue(e.getCode().contains("NoSuchBucket"));

    //Both source and dest bucket not found
    when(headers.getHeaderString(COPY_SOURCE_HEADER)).thenReturn(
        nonexist + "/" + urlEncode(keyName));
    e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(nonexist,
        destkey, CONTENT.length(), 1, null, body), "test copy object failed");
    assertTrue(e.getCode().contains("NoSuchBucket"));

    // source key not found
    when(headers.getHeaderString(COPY_SOURCE_HEADER)).thenReturn(
        bucketName + "/" + urlEncode(nonexist));
    e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(
        "nonexistent", keyName, CONTENT.length(), 1, null, body),
        "test copy object failed");
    assertTrue(e.getCode().contains("NoSuchBucket"));
  }

  @Test
  public void testCopyObjectMessageDigestResetDuringException() throws IOException, OS3Exception {
    // Put object in to source bucket
    HttpHeaders headers = mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);
    keyName = "sourceKey";

    Response response = objectEndpoint.put(bucketName, keyName,
        CONTENT.length(), 1, null, body);

    OzoneInputStream ozoneInputStream = clientStub.getObjectStore()
        .getS3Bucket(bucketName)
        .readKey(keyName);

    String keyContent = IOUtils.toString(ozoneInputStream, UTF_8);
    OzoneKeyDetails keyDetails = clientStub.getObjectStore().getS3Bucket(bucketName).getKey(keyName);

    assertEquals(200, response.getStatus());
    assertEquals(CONTENT, keyContent);
    assertNotNull(keyDetails.getMetadata());
    assertTrue(StringUtils.isNotEmpty(keyDetails.getMetadata().get(OzoneConsts.ETAG)));

    MessageDigest messageDigest = mock(MessageDigest.class);
    try (MockedStatic<IOUtils> mocked = mockStatic(IOUtils.class)) {
      // Add the mocked methods only during the copy request
      when(objectEndpoint.getMessageDigestInstance()).thenReturn(messageDigest);
      mocked.when(() -> IOUtils.copyLarge(any(InputStream.class), any(OutputStream.class)))
          .thenThrow(IOException.class);

      // Add copy header, and then call put
      when(headers.getHeaderString(COPY_SOURCE_HEADER)).thenReturn(
          bucketName  + "/" + urlEncode(keyName));

      try {
        objectEndpoint.put(destBucket, destkey, CONTENT.length(), 1,
            null, body);
        fail("Should throw IOException");
      } catch (IOException ignored) {
        // Verify that the message digest is reset so that the instance can be reused for the
        // next request in the same thread
        verify(messageDigest, times(1)).reset();
      }
    }
  }

  @Test
  public void testInvalidStorageType() throws IOException {
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);
    keyName = "sourceKey";
    when(headers.getHeaderString(STORAGE_CLASS_HEADER)).thenReturn("random");

    OS3Exception e = assertThrows(OS3Exception.class, () -> objectEndpoint.put(
        bucketName, keyName, CONTENT.length(), 1, null, body));
    assertEquals(S3ErrorTable.INVALID_ARGUMENT.getErrorMessage(),
        e.getErrorMessage());
    assertEquals("random", e.getResource());
  }

  @Test
  public void testEmptyStorageType() throws IOException, OS3Exception {
    HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    ByteArrayInputStream body =
        new ByteArrayInputStream(CONTENT.getBytes(UTF_8));
    objectEndpoint.setHeaders(headers);
    keyName = "sourceKey";
    when(headers.getHeaderString(STORAGE_CLASS_HEADER)).thenReturn("");

    objectEndpoint.put(bucketName, keyName, CONTENT
            .length(), 1, null, body);
    OzoneKeyDetails key =
        clientStub.getObjectStore().getS3Bucket(bucketName)
            .getKey(keyName);


    //default type is set
    assertEquals(ReplicationType.RATIS, key.getReplicationType());
  }

  @Test
  public void testDirectoryCreation() throws IOException,
      OS3Exception {
    // GIVEN
    final String path = "dir";
    final long length = 0L;
    final int partNumber = 0;
    final String uploadId = "";
    final InputStream body = null;
    final HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    final ObjectEndpoint objEndpoint = new ObjectEndpoint();
    objEndpoint.setOzoneConfiguration(new OzoneConfiguration());
    objEndpoint.setHeaders(headers);
    final OzoneClient client = Mockito.mock(OzoneClient.class);
    objEndpoint.setClient(client);
    final ObjectStore objectStore = Mockito.mock(ObjectStore.class);
    final OzoneVolume volume = Mockito.mock(OzoneVolume.class);
    final OzoneBucket bucket = Mockito.mock(OzoneBucket.class);
    final ClientProtocol protocol = Mockito.mock(ClientProtocol.class);

    // WHEN
    when(client.getObjectStore()).thenReturn(objectStore);
    when(client.getObjectStore().getS3Volume()).thenReturn(volume);
    when(volume.getBucket(bucketName)).thenReturn(bucket);
    when(bucket.getBucketLayout())
        .thenReturn(BucketLayout.FILE_SYSTEM_OPTIMIZED);
    when(client.getProxy()).thenReturn(protocol);
    final Response response = objEndpoint.put(bucketName, path, length,
        partNumber, uploadId, body);

    // THEN
    assertEquals(HttpStatus.SC_OK, response.getStatus());
    Mockito.verify(protocol).createDirectory(any(), eq(bucketName), eq(path));
  }

  @Test
  public void testDirectoryCreationOverFile() throws IOException {
    // GIVEN
    final String path = "key";
    final long length = 0L;
    final int partNumber = 0;
    final String uploadId = "";
    final ByteArrayInputStream body =
        new ByteArrayInputStream("content".getBytes(UTF_8));
    final HttpHeaders headers = Mockito.mock(HttpHeaders.class);
    final ObjectEndpoint objEndpoint = new ObjectEndpoint();
    objEndpoint.setOzoneConfiguration(new OzoneConfiguration());
    objEndpoint.setHeaders(headers);
    final OzoneClient client = Mockito.mock(OzoneClient.class);
    objEndpoint.setClient(client);
    final ObjectStore objectStore = Mockito.mock(ObjectStore.class);
    final OzoneVolume volume = Mockito.mock(OzoneVolume.class);
    final OzoneBucket bucket = Mockito.mock(OzoneBucket.class);
    final ClientProtocol protocol = Mockito.mock(ClientProtocol.class);

    // WHEN
    when(client.getObjectStore()).thenReturn(objectStore);
    when(client.getObjectStore().getS3Volume()).thenReturn(volume);
    when(volume.getBucket(bucketName)).thenReturn(bucket);
    when(bucket.getBucketLayout())
        .thenReturn(BucketLayout.FILE_SYSTEM_OPTIMIZED);
    when(client.getProxy()).thenReturn(protocol);
    doThrow(new OMException(OMException.ResultCodes.FILE_ALREADY_EXISTS))
        .when(protocol)
        .createDirectory(any(), any(), any());

    // THEN
    final OS3Exception exception = assertThrows(OS3Exception.class,
        () -> objEndpoint
            .put(bucketName, path, length, partNumber, uploadId, body));
    assertEquals("Conflict", exception.getCode());
    assertEquals(409, exception.getHttpCode());
    Mockito.verify(protocol, times(1)).createDirectory(any(), any(), any());
  }
}
