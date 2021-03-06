/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package org.radix.network2.addressbook;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.radixdlt.common.EUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.radix.events.Events;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.network.Interfaces;
import org.radix.network.discovery.BootstrapDiscovery;
import org.radix.network.messages.GetPeersMessage;
import org.radix.network.messages.PeerPingMessage;
import org.radix.network.messages.PeerPongMessage;
import org.radix.network.messages.PeersMessage;
import org.radix.network.messaging.Message;
import org.radix.network.peers.events.PeerAvailableEvent;
import org.radix.network2.messaging.MessageCentral;
import org.radix.network2.messaging.MessageListener;
import org.radix.network2.transport.TransportInfo;
import org.radix.network2.transport.TransportMetadata;
import org.radix.network2.transport.udp.UDPConstants;
import org.radix.properties.RuntimeProperties;
import org.radix.serialization.RadixTest;
import org.radix.serialization.TestSetupUtils;
import org.radix.time.Timestamps;
import org.radix.universe.system.RadixSystem;
import org.radix.universe.system.SystemMessage;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.when;

public class PeerManagerTest extends RadixTest {
    private static final Logger log = Logging.getLogger("PeerManagerTest");

    private MessageCentral messageCentral;
    private PeerManager peerManager;
    private AddressBook addressBook;
    private Events events;
    private BootstrapDiscovery bootstrapDiscovery;
    private PeerManagerConfiguration config;
    private Map<Class<Message>, MessageListener<Message>> messageListenerRegistry;
    private Peer peer1;
    private Peer peer2;
    private Peer peer3;
    private Peer peer4;
    private TransportInfo transportInfo1;
    private TransportInfo transportInfo2;
    private TransportInfo transportInfo3;
    private TransportInfo transportInfo4;
    private ArgumentCaptor<Peer> peerArgumentCaptor;
    private ArgumentCaptor<Message> messageArgumentCaptor;
    private Multimap<Peer, Message> peerMessageMultimap;
    private Interfaces interfaces;
    private EUID self = EUID.ZERO;
    private SecureRandom rng;

    @BeforeClass
    public static void beforeClass() {
    	// This takes a relatively long time to read the encrypted key store now
    	// on first construction, so make sure we pre-initialise here before
    	// running timing critical tests.
    	TestSetupUtils.installBouncyCastleProvider();
    	long start = System.nanoTime();
    	long finish = System.nanoTime();
    	System.out.format("%.3f seconds to initialise%n", (finish - start) / 1E9);
    }

    @Before
    public void setUp() {
        interfaces = mock(Interfaces.class);
        when(interfaces.isSelf(any())).thenReturn(false);
        when(getUniverse().getPlanck()).thenReturn(10000L);
        RuntimeProperties properties = getProperties();

        when(properties.get(eq("network.peers.heartbeat.delay"), any())).thenReturn(100);
        when(properties.get(eq("network.peers.heartbeat.interval"), any())).thenReturn(200);

        when(properties.get(eq("network.peers.broadcast.delay"), any())).thenReturn(100);
        when(properties.get(eq("network.peers.broadcast.interval"), any())).thenReturn(200);

        when(properties.get(eq("network.peers.probe.delay"), any())).thenReturn(100);
        when(properties.get(eq("network.peers.probe.interval"), any())).thenReturn(200);
        when(properties.get(eq("network.peers.probe.frequency"), any())).thenReturn(300);

        when(properties.get(eq("network.peers.discover.delay"), any())).thenReturn(100);
        when(properties.get(eq("network.peers.discover.interval"), any())).thenReturn(200);

        when(properties.get(eq("network.peers.message.batch.size"), any())).thenReturn(2);

        config = spy(PeerManagerConfiguration.fromRuntimeProperties(properties));
        peerMessageMultimap = LinkedListMultimap.create();
        messageCentral = mock(MessageCentral.class);
        events = mock(Events.class);
        rng = mock(SecureRandom.class);

        messageListenerRegistry = new HashMap<>();
        doAnswer(invocation -> {
            messageListenerRegistry.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(messageCentral).addListener(any(), any());

        peerArgumentCaptor = ArgumentCaptor.forClass(Peer.class);
        messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            peerMessageMultimap.put(invocation.getArgument(0), invocation.getArgument(1));
            MessageListener<Message> messageListener = messageListenerRegistry.get(invocation.getArgument(1).getClass());
            messageListener.handleMessage(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(messageCentral).send(peerArgumentCaptor.capture(), messageArgumentCaptor.capture());


        TransportMetadata transportMetadata1 = TransportMetadata.create(ImmutableMap.of("host", "192.168.0.1"));
        transportInfo1 = TransportInfo.of(UDPConstants.UDP_NAME, transportMetadata1);
        TransportMetadata transportMetadata2 = TransportMetadata.create(ImmutableMap.of("host", "192.168.0.2"));
        transportInfo2 = TransportInfo.of(UDPConstants.UDP_NAME, transportMetadata2);
        TransportMetadata transportMetadata3 = TransportMetadata.create(ImmutableMap.of("host", "192.168.0.3"));
        transportInfo3 = TransportInfo.of(UDPConstants.UDP_NAME, transportMetadata3);
        TransportMetadata transportMetadata4 = TransportMetadata.create(ImmutableMap.of("host", "192.168.0.4"));
        transportInfo4 = TransportInfo.of(UDPConstants.UDP_NAME, transportMetadata4);

        RadixSystem radixSystem1 = spy(new RadixSystem());
        when(radixSystem1.supportedTransports()).thenAnswer((Answer<Stream<TransportInfo>>) invocation -> Stream.of(transportInfo1));
        when(radixSystem1.getNID()).thenReturn(EUID.ONE);
        peer1 = spy(new PeerWithSystem(radixSystem1));
        when(peer1.getTimestamp(Timestamps.ACTIVE)).thenAnswer((Answer<Long>) invocation -> System.currentTimeMillis());

        RadixSystem radixSystem2 = spy(new RadixSystem());
        when(radixSystem2.supportedTransports()).thenAnswer((Answer<Stream<TransportInfo>>) invocation -> Stream.of(transportInfo2));
        when(radixSystem2.getNID()).thenReturn(EUID.TWO);
        peer2 = spy(new PeerWithSystem(radixSystem2));
        when(peer2.getTimestamp(Timestamps.ACTIVE)).thenAnswer((Answer<Long>) invocation -> System.currentTimeMillis());

        RadixSystem radixSystem3 = spy(new RadixSystem());
        when(radixSystem3.supportedTransports()).thenAnswer((Answer<Stream<TransportInfo>>) invocation -> Stream.of(transportInfo3));
        when(radixSystem3.getNID()).thenReturn(new EUID(3));
        peer3 = spy(new PeerWithSystem(radixSystem3));
        when(peer3.getTimestamp(Timestamps.ACTIVE)).thenAnswer((Answer<Long>) invocation -> System.currentTimeMillis());

        RadixSystem radixSystem4 = spy(new RadixSystem());
        when(radixSystem4.supportedTransports()).thenAnswer((Answer<Stream<TransportInfo>>) invocation -> Stream.of(transportInfo4));
        when(radixSystem4.getNID()).thenReturn(new EUID(4));
        peer4 = spy(new PeerWithSystem(radixSystem4));
        when(peer4.getTimestamp(Timestamps.ACTIVE)).thenAnswer((Answer<Long>) invocation -> System.currentTimeMillis());

        addressBook = mock(AddressBook.class);
        when(addressBook.peer(transportInfo1)).thenReturn(peer1);
        when(addressBook.peer(transportInfo2)).thenReturn(peer2);
        when(addressBook.peer(transportInfo3)).thenReturn(peer3);
        when(addressBook.peer(transportInfo4)).thenReturn(peer4);

        bootstrapDiscovery = mock(BootstrapDiscovery.class);
        peerManager = spy(new PeerManager(config, addressBook, messageCentral, events, bootstrapDiscovery, rng, self, getLocalSystem(), interfaces, properties, getUniverse()));
    }

    @After
    public void tearDown() {
        peerManager.stop();
    }

    @Test
    public void heartbeatPeersTest() throws InterruptedException {
        when(addressBook.recentPeers()).thenAnswer((Answer<Stream<Peer>>) invocation -> Stream.of(peer1, peer2));

        Semaphore semaphore = new Semaphore(0);
        //allow peer manager to run 1 sec
        peerManager.start();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                peerManager.stop();
                semaphore.release();
            }
        }, 1000);
        semaphore.acquire();

        List<SystemMessage> peer1SystemMessages = peerMessageMultimap.get(peer1).stream().
                filter(message -> message instanceof SystemMessage).map(message -> (SystemMessage) message).collect(Collectors.toList());
        List<SystemMessage> peer2SystemMessages = peerMessageMultimap.get(peer2).stream().
                filter(message -> message instanceof SystemMessage).map(message -> (SystemMessage) message).collect(Collectors.toList());


        //in 1 sec of execution with network.peers.heartbeat.delay = 100 and network.peers.heartbeat.interval = 200
        //heartbeat message should be sent 4 times for each peer (1000-100)/200 = 4.5
        //message delivery could be late and last few messages of SystemMessage could be lost or
        //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(peer1SystemMessages.size()).isCloseTo(4, offset(1));
            softly.assertThat(peer2SystemMessages.size()).isCloseTo(4, offset(1));
        });

    }

    @Test
    public void peersHousekeepingTest() throws InterruptedException {
        when(addressBook.recentPeers()).thenAnswer((Answer<Stream<Peer>>) invocation -> Stream.of(peer1, peer2));
        when(addressBook.peers()).thenAnswer((Answer<Stream<Peer>>) invocation -> Stream.of(peer1, peer2, peer3, peer4));
        getPeersMessageTest(peer1, peer2, false);
    }

    @Test
    public void probeTaskTest() throws InterruptedException {
        when(addressBook.peers()).thenAnswer((Answer<Stream<Peer>>) invocation -> Stream.of(peer1, peer2));
        Semaphore semaphore = new Semaphore(0);
        peerManager.start();
        //allow peer manager to run 1 sec
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                peerManager.stop();
                semaphore.release();
            }
        }, 1000);
        semaphore.acquire();

        List<PeerPingMessage> peer1PeerPingMessages = peerMessageMultimap.get(peer1).stream().
                filter(message -> message instanceof PeerPingMessage).map(message -> (PeerPingMessage) message).collect(Collectors.toList());
        List<PeerPingMessage> peer2PeerPingMessages = peerMessageMultimap.get(peer2).stream().
                filter(message -> message instanceof PeerPingMessage).map(message -> (PeerPingMessage) message).collect(Collectors.toList());
        List<PeerPongMessage> peer1PeerPongMessages = peerMessageMultimap.get(peer1).stream().
                filter(message -> message instanceof PeerPongMessage).map(message -> (PeerPongMessage) message).collect(Collectors.toList());
        List<PeerPongMessage> peer2PeerPongMessages = peerMessageMultimap.get(peer2).stream().
                filter(message -> message instanceof PeerPongMessage).map(message -> (PeerPongMessage) message).collect(Collectors.toList());

        SoftAssertions.assertSoftly(softly -> {
            //in 1 sec of execution with network.peer.probe.delay = 100 and network.peer.probe.interval = 200
            //Ping message will be initiated by executor at least 4 times (1000-100)/200 = 4.5
            //but because of network.peer.probe.frequency = 300 parameter we expect 2 requests only
            //message delivery could be late and last few messages of PeerPing/PeerPong could be lost or
            //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
            softly.assertThat(peer1PeerPingMessages.size()).isCloseTo(2, offset(1));
            softly.assertThat(peer2PeerPingMessages.size()).isCloseTo(2, offset(1));

            //each ping will receive pong message
            softly.assertThat(peer1PeerPongMessages.size()).isCloseTo(2, offset(1));
            softly.assertThat(peer2PeerPongMessages.size()).isCloseTo(2, offset(1));

        });
        //For each Ping and Pong Message we are broadcasting PeerAvailableEvent. Expected number of invocation is 8
        //message delivery could be late and last few messages of PeerPing could be lost or
        //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
        verify(events, atLeast(8)).broadcast(any(PeerAvailableEvent.class));
        verify(events, atMost(12)).broadcast(any(PeerAvailableEvent.class));
    }

    @Test
    public void handleProbeTimeoutTest() throws InterruptedException {
        when(addressBook.peers()).thenAnswer((Answer<Stream<Peer>>) invocation -> Stream.of(peer1, peer2));
        //start timeout handler immediately
        doReturn(0).when(config).networkPeersProbeTimeout(eq(20000));
        peerManager = spy(new PeerManager(config, addressBook, messageCentral, events, bootstrapDiscovery, rng, self, getLocalSystem(), interfaces, getProperties(), getUniverse()));
        Semaphore semaphore = new Semaphore(0);
        peerManager.start();
        //allow peer manager to run 1 sec
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                peerManager.stop();
                semaphore.release();
            }
        }, 1000);
        semaphore.acquire();
        verify(addressBook, atLeast(1)).removePeer(peer1);
        verify(addressBook, atLeast(1)).removePeer(peer2);
    }

    @Test
    public void discoverPeersTest() throws InterruptedException {
        when(bootstrapDiscovery.discover(eq(addressBook), any())).thenReturn(ImmutableSet.of(transportInfo3, transportInfo4));
        when(addressBook.peers()).thenAnswer((Answer<Stream<Peer>>) invocation -> Stream.of(peer1, peer2, peer3, peer4));
        getPeersMessageTest(peer3, peer4, true);
    }

    private void getPeersMessageTest(Peer peer1, Peer peer2, boolean probeAll) throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        peerManager.start();
        //allow peer manager to run 1 sec
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                peerManager.stop();
                semaphore.release();
            }
        }, 1000);
        semaphore.acquire();

        List<GetPeersMessage> peer1GetPeersMessages = peerMessageMultimap.get(peer1).stream().
                filter(message -> message instanceof GetPeersMessage).map(message -> (GetPeersMessage) message).collect(Collectors.toList());
        List<GetPeersMessage> peer2GetPeersMessages = peerMessageMultimap.get(peer2).stream().
                filter(message -> message instanceof GetPeersMessage).map(message -> (GetPeersMessage) message).collect(Collectors.toList());

        List<PeersMessage> peer1PeersMessage = peerMessageMultimap.get(peer1).stream().
                filter(message -> message instanceof PeersMessage).map(message -> (PeersMessage) message).collect(Collectors.toList());
        List<PeersMessage> peer2PeersMessage = peerMessageMultimap.get(peer2).stream().
                filter(message -> message instanceof PeersMessage).map(message -> (PeersMessage) message).collect(Collectors.toList());

        SoftAssertions.assertSoftly(softly -> {

            //in 1 sec of execution with network.peers.broadcast.delay = 100 and network.peers.broadcast.interval = 200
            //if probeAll = false then only one peer will be selected from recentPeers to send message randomly. GetPeersMessage message should be sent 4 times (1000-100)/200 = 4
            //if probeAll = true then all peers from list will be discovered GetPeersMessage message should be sent 8 times times (1000-100)/200 = 4 then  4*2 = 8
            //message delivery could be late and last few messages of GetPeerMessage/PeerMessage could be lost or
            //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
            int expectedNumberOfGetPeersMessages = probeAll ? 8 : 4;
            //number of responses is equal to 2 * number of requests
            //because we are using network.peers.message.batch.size = 2
            int expectedNumberOfPeersMessage = expectedNumberOfGetPeersMessages * 2;
            softly.assertThat(peer1GetPeersMessages.size() + peer2GetPeersMessages.size()).isCloseTo(expectedNumberOfGetPeersMessages, offset(2));
            softly.assertThat(peer1PeersMessage.size() + peer2PeersMessage.size()).isCloseTo(expectedNumberOfPeersMessage, offset(2 * 2));

            //number of responses is equal to 2 * number of requests
            //because we are using network.peers.message.batch.size = 2
            //each response should have 3 peer so we will have 2 message for each response with 2 and 1 peer accordingly
            //message delivery could be late and last few messages of GetPeerMessage/PeerMessage could be lost or
            //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
            softly.assertThat(peer1GetPeersMessages.size() * 2).isCloseTo(peer1PeersMessage.size(), offset(2));
            softly.assertThat(peer2GetPeersMessages.size() * 2).isCloseTo(peer2PeersMessage.size(), offset(2));

            //each response exclude self
            //because we are using network.peers.message.batch.size = 2 batch is <= 2
            //each response should have 3 peer so we will have 2 message for each response with 2 and 1 peer accordingly
            //total number of peers = number of getPeers requests * 3
            AtomicInteger peerNumber = new AtomicInteger(0);
            peer1PeersMessage.forEach(message -> {
                softly.assertThat(message.getPeers()).doesNotContain(peer1);
                softly.assertThat(message.getPeers().size()).isBetween(1, 2);
                peerNumber.addAndGet(message.getPeers().size());
            });
            //message delivery could be late and last few messages of GetPeerMessage/PeerMessage could be lost or
            //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
            softly.assertThat(peerNumber.get()).isCloseTo(peer1GetPeersMessages.size() * 3, offset(3));

            peerNumber.set(0);
            peer2PeersMessage.forEach(message -> {
                softly.assertThat(message.getPeers()).doesNotContain(peer2);
                softly.assertThat(message.getPeers().size()).isBetween(1, 2);
                peerNumber.addAndGet(message.getPeers().size());
            });
            //message delivery could be late and last few messages of GetPeerMessage/PeerMessage could be lost or
            //could be executed more times as peerManager started before we scheduling stop operation and some messages could be sent before moment when we are starting to count
            softly.assertThat(peerNumber.get()).isCloseTo(peer2GetPeersMessages.size() * 3, offset(3));
        });
    }

}