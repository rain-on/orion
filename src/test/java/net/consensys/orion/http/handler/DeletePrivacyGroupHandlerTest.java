/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package net.consensys.orion.http.handler;

import static net.consensys.cava.io.Base64.encodeBytes;
import static net.consensys.orion.http.server.HttpContentType.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.consensys.cava.crypto.sodium.Box;
import net.consensys.orion.enclave.Enclave;
import net.consensys.orion.enclave.PrivacyGroupPayload;
import net.consensys.orion.enclave.sodium.MemoryKeyStore;
import net.consensys.orion.enclave.sodium.SodiumEnclave;
import net.consensys.orion.helpers.FakePeer;
import net.consensys.orion.http.handler.privacy.DeletePrivacyGroupRequest;
import net.consensys.orion.http.handler.privacy.PrivacyGroup;
import net.consensys.orion.http.handler.privacy.PrivacyGroupRequest;
import net.consensys.orion.utils.Serializer;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DeletePrivacyGroupHandlerTest extends HandlerTest {
  private MemoryKeyStore memoryKeyStore;
  private String privacyGroupId;
  private FakePeer fakePeer;
  private Box.PublicKey senderKey;

  @Override
  protected Enclave buildEnclave(Path tempDir) {
    memoryKeyStore = new MemoryKeyStore();
    Box.PublicKey defaultNodeKey = memoryKeyStore.generateKeyPair();
    memoryKeyStore.addNodeKey(defaultNodeKey);
    return new SodiumEnclave(memoryKeyStore);
  }

  @BeforeEach
  void setup() throws IOException, InterruptedException {
    senderKey = memoryKeyStore.generateKeyPair();
    Box.PublicKey recipientKey = memoryKeyStore.generateKeyPair();

    String[] toEncrypt = new String[] {encodeBytes(senderKey.bytesArray()), encodeBytes(recipientKey.bytesArray())};
    PrivacyGroupRequest privacyGroupRequestExpected =
        buildPrivacyGroupRequest(toEncrypt, encodeBytes(senderKey.bytesArray()), "test", "desc");
    Request request = buildPrivateAPIRequest("/createPrivacyGroup", JSON, privacyGroupRequestExpected);

    byte[] privacyGroupPayload = enclave.generatePrivacyGroupId(
        new Box.PublicKey[] {senderKey, recipientKey},
        privacyGroupRequestExpected.getSeed().get(),
        PrivacyGroupPayload.Type.PANTHEON);

    // create fake peer
    fakePeer = new FakePeer(new MockResponse().setBody(encodeBytes(privacyGroupPayload)), recipientKey);
    networkNodes.addNode(fakePeer.publicKey, fakePeer.getURL());

    // execute request
    Response resp = httpClient.newCall(request).execute();

    assertEquals(200, resp.code());

    RecordedRequest recordedRequest = fakePeer.server.takeRequest();
    assertEquals("/pushPrivacyGroup", recordedRequest.getPath());
    assertEquals("POST", recordedRequest.getMethod());

    PrivacyGroup privacyGroup = Serializer.deserialize(JSON, PrivacyGroup.class, resp.body().bytes());
    privacyGroupId = privacyGroup.getPrivacyGroupId();
  }

  @Test
  void expectedDeletePrivacyGroupId() throws Exception {

    DeletePrivacyGroupRequest deletePrivacyGroupRequest =
        buildDeletePrivacyGroupRequest(privacyGroupId, encodeBytes(senderKey.bytesArray()));

    Request request = buildPrivateAPIRequest("/deletePrivacyGroup", JSON, deletePrivacyGroupRequest);

    fakePeer.addResponse(new MockResponse().setBody(privacyGroupId));
    // execute request
    Response resp = httpClient.newCall(request).execute();

    assertEquals(200, resp.code());
  }

  @Test
  void expectErrorDeletePrivacyGroupIdTwice() throws Exception {

    DeletePrivacyGroupRequest deletePrivacyGroupRequest =
        buildDeletePrivacyGroupRequest(privacyGroupId, encodeBytes(senderKey.bytesArray()));

    Request request = buildPrivateAPIRequest("/deletePrivacyGroup", JSON, deletePrivacyGroupRequest);

    fakePeer.addResponse(new MockResponse().setBody(privacyGroupId));
    // execute request
    Response resp = httpClient.newCall(request).execute();

    assertEquals(200, resp.code());

    // execute the same request again
    resp = httpClient.newCall(request).execute();

    assertEquals(500, resp.code());
  }

  @Test
  void expectErrorDeleteIncorrectPrivacyGroupId() throws Exception {

    DeletePrivacyGroupRequest deletePrivacyGroupRequest =
        buildDeletePrivacyGroupRequest("test", encodeBytes(senderKey.bytesArray()));

    Request request = buildPrivateAPIRequest("/deletePrivacyGroup", JSON, deletePrivacyGroupRequest);

    // execute request
    Response resp = httpClient.newCall(request).execute();

    assertEquals(500, resp.code());
  }


  PrivacyGroupRequest buildPrivacyGroupRequest(String[] addresses, String from, String name, String description) {
    PrivacyGroupRequest privacyGroupRequest = new PrivacyGroupRequest(addresses, from, name, description);
    // create a random seed
    SecureRandom random = new SecureRandom();
    byte[] bytes = new byte[20];
    random.nextBytes(bytes);
    privacyGroupRequest.setSeed(bytes);

    return privacyGroupRequest;
  }

  DeletePrivacyGroupRequest buildDeletePrivacyGroupRequest(String key, String from) {
    return new DeletePrivacyGroupRequest(key, from);
  }
}
