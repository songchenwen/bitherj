/*
* Copyright 2014 http://Bither.net
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package net.bither.bitherj.core;

import net.bither.bitherj.db.PeerProvider;
import net.bither.bitherj.db.TxProvider;
import net.bither.bitherj.exception.ProtocolException;
import net.bither.bitherj.exception.ScriptException;
import net.bither.bitherj.exception.VerificationException;
import net.bither.bitherj.message.AlertMessage;
import net.bither.bitherj.message.BlockMessage;
import net.bither.bitherj.message.FilteredBlockMessage;
import net.bither.bitherj.message.GetAddrMessage;
import net.bither.bitherj.message.GetBlocksMessage;
import net.bither.bitherj.message.GetDataMessage;
import net.bither.bitherj.message.GetHeadersMessage;
import net.bither.bitherj.message.HeadersMessage;
import net.bither.bitherj.message.InventoryMessage;
import net.bither.bitherj.message.MemoryPoolMessage;
import net.bither.bitherj.message.Message;
import net.bither.bitherj.message.NotFoundMessage;
import net.bither.bitherj.message.PingMessage;
import net.bither.bitherj.message.PongMessage;
import net.bither.bitherj.message.RejectMessage;
import net.bither.bitherj.message.VersionAck;
import net.bither.bitherj.message.VersionMessage;
import net.bither.bitherj.net.NioClientManager;
import net.bither.bitherj.net.PeerSocketHandler;
import net.bither.bitherj.script.Script;
import net.bither.bitherj.utils.InventoryItem;
import net.bither.bitherj.utils.LogUtil;
import net.bither.bitherj.utils.Sha256Hash;
import net.bither.bitherj.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Created by zhouqi on 14-8-15.
 */
public class Peer extends PeerSocketHandler {
    private static final int MAX_GETDATA_HASHES = 50000;

    private static final Logger log = LoggerFactory.getLogger(Peer.class);
    private static final int TimeOutDelay = 5000;

    enum State {
        Disconnected, Connecting, Connected
    }

    enum DisconnectReason {
        Normal, NoneProtocol, Timeout
    }

    public State state;

    protected InetAddress peerAddress;
    protected int peerTimestamp;
    protected int peerPort;
    protected long peerServices;
    protected int peerConnectedCnt;
    protected long lastBlockHeight;
    protected int version;
    protected long nonce;
    protected String userAgent;

    public long pingTime;
    private long pingStartTime;
    private int timestamp;
    private int filterBlockCount;

    private boolean sentVerAck, gotVerAck;

    private final HashSet<Sha256Hash> currentTxHashes, knownTxHashes, requestedBlockHashes;
    private final LinkedHashSet<Sha256Hash> currentBlockHashes;
    private final HashMap<Sha256Hash, HashSet<Tx>> needToRequestDependencyDict;
    private Block currentFilteredBlock;
    private VersionMessage versionMessage;
    private boolean bloomFilterSent;


    public Peer(InetAddress address) {
        super(new InetSocketAddress(address, BitherjSettings.port));
        this.peerAddress = address;
        peerPort = BitherjSettings.port;
        state = State.Disconnected;
        peerServices = 1;
        currentTxHashes = new HashSet<Sha256Hash>();
        currentBlockHashes = new LinkedHashSet<Sha256Hash>();
        knownTxHashes = new HashSet<Sha256Hash>();
        requestedBlockHashes = new HashSet<Sha256Hash>();
        needToRequestDependencyDict = new HashMap<Sha256Hash, HashSet<Tx>>();
        nonce = new Random().nextLong();
        peerTimestamp = (int) (new Date().getTime() / 1000 - 24 * 60 * 60 * (3 + new Random()
                .nextFloat() * 4));
    }

    public void connect() {
        if (state != State.Disconnected) {
            log.info("peer[{}:{}] call connect, but its state is not disconnected",
                    this.peerAddress.getHostAddress(), this.peerPort);
            return;
        } else {
            log.info("peer[{}:{}] call connect", this.peerAddress.getHostAddress(), this.peerPort);
            state = State.Connecting;
            if (!NioClientManager.instance().isRunning()) {
                NioClientManager.instance().startAndWait();
            }
            setTimeoutEnabled(true);
            setSocketTimeout(TimeOutDelay);
            bloomFilterSent = false;
            NioClientManager.instance().openConnection(new InetSocketAddress(getPeerAddress(),
                    BitherjSettings.port), this);
        }
    }

    public void disconnect() {
        if (state == State.Disconnected) {
            return;
        } else {
            log.info("peer[{}:{}] call disconnect", this.peerAddress.getHostAddress(),
                    this.peerPort);
            state = State.Disconnected;
            close();
        }
    }

    @Override
    protected void processMessage(Message m) throws Exception {
        if (m == null) {
            return;
        }

        if (currentFilteredBlock != null && !(m instanceof Tx)) {
            currentFilteredBlock = null;
            currentTxHashes.clear();
            exceptionCaught(new ProtocolException("Except more tx for current filtering block"));
        }

        if (m instanceof NotFoundMessage) {
            // This is sent to us when we did a getdata on some transactions that aren't in the
            // peers memory pool.
            // Because NotFoundMessage is a subclass of InventoryMessage,
            // the test for it must come before the next.
            processNotFoundMessage((NotFoundMessage) m);
        } else if (m instanceof InventoryMessage) {
            processInv((InventoryMessage) m);
        } else if (m instanceof BlockMessage) {
            processBlock((BlockMessage) m);
        } else if (m instanceof FilteredBlockMessage) {
            startFilteredBlock((FilteredBlockMessage) m);
        } else if (m instanceof Tx) {
            processTransaction((Tx) m);
        } else if (m instanceof GetDataMessage) {
            processGetData((GetDataMessage) m);
        } else if (m instanceof HeadersMessage) {
            processHeaders((HeadersMessage) m);
        } else if (m instanceof AlertMessage) {
            processAlert((AlertMessage) m);
        } else if (m instanceof VersionMessage) {
            processVersionMessage((VersionMessage) m);
        } else if (m instanceof VersionAck) {
            if (!sentVerAck) {
                throw new ProtocolException("got a version ack before version");
            } else if (gotVerAck) {
                throw new ProtocolException("got more than one version ack");
            }
            gotVerAck = true;
            setTimeoutEnabled(false);
            state = State.Connected;
            PeerManager.instance().peerConnected(this);
            ping();
        } else if (m instanceof PingMessage) {
            if (((PingMessage) m).hasNonce()) {
                sendMessage(new PongMessage(((PingMessage) m).getNonce()));
            }
        } else if (m instanceof PongMessage) {
            processPong((PongMessage) m);
        } else if (m instanceof RejectMessage) {
            processReject((RejectMessage) m);
        }
    }

    private void processNotFoundMessage(NotFoundMessage m) {
        // This is received when we previously did a getdata but the peer couldn't find what we
        // requested in it's
        // memory pool. Typically, because we are downloading dependencies of a relevant
        // transaction and reached
        // the bottom of the dependency tree (where the unconfirmed transactions connect to
        // transactions that are
        // in the chain).
        //
        // We go through and cancel the pending getdata futures for the items we were told
        // weren't found.
        log.info("peer[{}:{}] receive {} notfound item ", this.peerAddress.getHostAddress(),
                this.peerPort, m.getItems().size());
        for (InventoryItem item : m.getItems()) {
            if (item.type == InventoryItem.Type.Transaction && item.hash != null && item.hash
                    .length > 0) {
                checkDependencyWithNotFoundMsg(new Sha256Hash(item.hash));
            }
        }
    }

    private void checkDependencyWithNotFoundMsg(Sha256Hash hash) {
        HashSet<Tx> needCheckDependencyTxs = needToRequestDependencyDict.get(hash);
        if (needCheckDependencyTxs == null) {
            return;
        } else {
            needToRequestDependencyDict.remove(hash);
        }
        HashSet<Tx> checkedTxs = new HashSet<Tx>();

        for (Tx eachTx : needCheckDependencyTxs) {
            boolean stillNeedDependency = false;
            for (HashSet<Tx> set : needToRequestDependencyDict.values()) {
                if (set.contains(eachTx)) {
                    stillNeedDependency = true;
                    break;
                }
            }
            if (!stillNeedDependency) {
                PeerManager.instance().relayedTransaction(this, eachTx);
                checkedTxs.add(eachTx);
            }
        }
        for (Tx eachTx : checkedTxs) {
            checkDependencyWith(eachTx);
        }
    }

    private void checkDependencyWith(Tx tx) {
        HashSet<Tx> needCheckDependencyTxs = needToRequestDependencyDict.get(new Sha256Hash(tx
                .getTxHash()));
        if (needCheckDependencyTxs == null) {
            return;
        } else {
            needToRequestDependencyDict.remove(new Sha256Hash(tx.getTxHash()));
        }
        HashSet<Tx> invalidTxs = new HashSet<Tx>();
        HashSet<Tx> checkedTxs = new HashSet<Tx>();
        for (Tx eachTx : needCheckDependencyTxs) {
            boolean valid = true;
            for (int i = 0;
                 i < eachTx.getIns().size();
                 i++) {
                if (Arrays.equals(eachTx.getIns().get(i).getTxHash(), eachTx.getTxHash())) {
                    byte[] outScript = tx.getOuts().get(eachTx.getIns().get(i).getInSn())
                            .getOutScript();
                    Script pubKeyScript = new Script(outScript);
                    Script script = new Script(eachTx.getIns().get(i).getInSignature());
                    try {
                        script.correctlySpends(eachTx, i, pubKeyScript, true);
                        valid &= true;
                    } catch (ScriptException e) {
                        valid &= false;
                    }
                } else {
                    valid = false;
                }
                if (!valid) {
                    break;
                }
            }
            if (valid) {
                boolean stillNeedDependency = false;
                for (HashSet<Tx> set : needToRequestDependencyDict.values()) {
                    if (set.contains(eachTx)) {
                        stillNeedDependency = true;
                        break;
                    }
                }
                if (!stillNeedDependency) {
                    PeerManager.instance().relayedTransaction(this, eachTx);
                    checkedTxs.add(eachTx);
                }
            } else {
                invalidTxs.add(eachTx);
            }
        }
        for (Tx eachTx : invalidTxs) {
            log.warn(getPeerAddress().getHostAddress() + "tx:" + Utils.bytesToHexString(eachTx
                    .getTxHash()) + " is invalid");
            clearInvalidTxFromDependencyDict(eachTx);
        }
        for (Tx eachTx : checkedTxs) {
            checkDependencyWith(eachTx);
        }
    }

    private void clearInvalidTxFromDependencyDict(Tx tx) {
        for (HashSet<Tx> set : needToRequestDependencyDict.values()) {
            if (set.contains(tx)) {
                set.remove(tx);
            }
        }
        HashSet<Tx> subTxs = needToRequestDependencyDict.get(new Sha256Hash(tx.getTxHash()));
        if (subTxs != null) {
            needToRequestDependencyDict.remove(new Sha256Hash(tx.getTxHash()));
            for (Tx eachTx : subTxs) {
                clearInvalidTxFromDependencyDict(eachTx);
            }
        }
    }

    private void processInv(InventoryMessage inv) {
        ArrayList<InventoryItem> items = new ArrayList<InventoryItem>(inv.getItems());

        if (items.size() == 0) {
            return;
        } else if (items.size() > MAX_GETDATA_HASHES) {
            log.info(this.getPeerAddress().getHostAddress() + " dropping inv message, " +
                    "" + items.size() + " is too many items, max is " + MAX_GETDATA_HASHES);
            return;
        }
        if(!bloomFilterSent){
            log.info("Peer {} received inv. But we didn't send bloomfilter. Ignore");
        }
        ArrayList<Sha256Hash> txHashSha256Hashs = new ArrayList<Sha256Hash>();
        ArrayList<Sha256Hash> blockHashSha256Hashs = new ArrayList<Sha256Hash>();
        for (InventoryItem item : items) {
            InventoryItem.Type type = item.type;
            byte[] hash = item.hash;
            if (hash == null || hash.length == 0) {
                continue;
            }
            switch (type) {
                case Transaction:
                    Sha256Hash big = new Sha256Hash(hash);
                    if (!txHashSha256Hashs.contains(big)) {
                        txHashSha256Hashs.add(big);
                    }
                    break;
                case Block:
                case FilteredBlock:
                    if(PeerManager.instance().getDownloadingPeer() == null || getDownloadData()) {
                        Sha256Hash bigBlock = new Sha256Hash(hash);
                        if (!blockHashSha256Hashs.contains(bigBlock)) {
                            blockHashSha256Hashs.add(bigBlock);
                        }
                    }
                    break;
            }
        }

        log.info(getPeerAddress().getHostAddress() + " got inv with " + items.size() + " items "
                + txHashSha256Hashs.size() + " tx " + blockHashSha256Hashs.size() + " block");

        if (txHashSha256Hashs.size() > 10000) {
            return;
        }
        // to improve chain download performance, if we received 500 block hashes,
        // we request the next 500 block hashes
        // immediately before sending the getdata request
        if (blockHashSha256Hashs.size() >= 500) {
            sendGetBlocksMessage(Arrays.asList(new Sha256Hash[]{blockHashSha256Hashs.get
                    (blockHashSha256Hashs.size() - 1), blockHashSha256Hashs.get(0)}), null);
        }

        txHashSha256Hashs.removeAll(knownTxHashes);
        knownTxHashes.addAll(txHashSha256Hashs);

        if (txHashSha256Hashs.size() + blockHashSha256Hashs.size() > 0) {
            sendGetDataMessageWithTxHashesAndBlockHashes(txHashSha256Hashs, blockHashSha256Hashs);

            // Each merkle block the remote peer sends us is followed by a set of tx messages for
            // that block. We send a ping
            // to get a pong reply after the block and all its tx are sent,
            // indicating that there are no more tx messages
            if (blockHashSha256Hashs.size() == 1) {
                ping();
            }
        }

        if (blockHashSha256Hashs.size() > 0) {
            //remember blockHashes in case we need to refetch them with an updated bloom filter
            currentBlockHashes.addAll(blockHashSha256Hashs);
            if (currentBlockHashes.size() > MAX_GETDATA_HASHES) {
                Iterator iterator = currentBlockHashes.iterator();
                while (iterator.hasNext() && currentBlockHashes.size() > MAX_GETDATA_HASHES / 2) {
                    iterator.remove();
                }
            }
        }
    }

    private void processBlock(BlockMessage m) {
        // we don't need to process block message after we send our awesome bloom filters.
        log.info("peer[{}:{}] receive block {}", this.peerAddress.getHostAddress(),
                this.peerPort, Utils.hashToString(m.getBlock().getBlockHash()));
    }

    private void startFilteredBlock(FilteredBlockMessage m) {
        Block block = m.getBlock();
        block.verifyHeader();

        log.info("peer[{}:{}] receive filtered block {} with {} tx",
                this.peerAddress.getHostAddress(), this.peerPort,
                Utils.hashToString(block.getBlockHash()), block.getTxHashes().size());

        currentBlockHashes.remove(new Sha256Hash(block.getBlockHash()));
        requestedBlockHashes.remove(new Sha256Hash(block.getBlockHash()));
        if (requestedBlockHashes.contains(new Sha256Hash(block.getBlockHash()))) {
            return;
        }
        ArrayList<Sha256Hash> txHashes = new ArrayList<Sha256Hash>();
        for (byte[] txHash : block.getTxHashes()) {
            txHashes.add(new Sha256Hash(txHash));
            log.info("peer[{}:{}] receive filtered block {} tx {}",
                    this.peerAddress.getHostAddress(), this.peerPort,
                    Utils.hashToString(m.getBlock().getBlockHash()), Utils.hashToString(txHash));
        }
        txHashes.removeAll(knownTxHashes);

        // wait util we get all the tx messages before processing the block
        if (txHashes.size() > 0) {
            currentFilteredBlock = block;
            currentTxHashes.clear();
            currentTxHashes.addAll(txHashes);
        } else {
            PeerManager.instance().relayedBlock(this, block);
        }
        if(currentBlockHashes.size() == 0){
            sendGetBlocksMessage(Arrays.asList(new byte[][]{block.getBlockHash(), BlockChain.getInstance().getBlockLocatorArray().get(0)}), null);
        }
    }

    private void processTransaction(Tx tx) throws VerificationException {
        if (currentFilteredBlock != null) { // we're collecting tx messages for a merkleblock
            PeerManager.instance().relayedTransaction(this, tx);
            // we can't we byte array hash or BigInteger as the key.
            // byte array can't be compared
            // BigInteger can't be cast back to byte array
            // so we use Sha256Hash class here as key
            boolean removed = currentTxHashes.remove(new Sha256Hash(tx.getTxHash()));
            log.info("peer[{}:{}] receive tx {} filtering block: {}, remaining tx {}, remove {}",
                    this.peerAddress.getHostAddress(), this.peerPort,
                    Utils.hashToString(tx.getTxHash()), Utils.hashToString(currentFilteredBlock
                            .getBlockHash()), currentTxHashes.size(),
                    removed ? "success" : "failed");
            if (currentTxHashes.size() == 0) { // we received the entire block including all
                // matched tx
                Block block = currentFilteredBlock;
                currentFilteredBlock = null;
                currentTxHashes.clear();
                PeerManager.instance().relayedBlock(this, block);
            }
        } else {
            log.info("peer[{}:{}] receive tx {}", this.peerAddress.getHostAddress(),
                    this.peerPort, Utils.hashToString(tx.getTxHash()));
            // check dependency
            HashMap<Sha256Hash, Tx> dependency = TxProvider.getInstance().getTxDependencies(tx);
            HashSet<Sha256Hash> needToRequest = new HashSet<Sha256Hash>();
            boolean valid = true;
            for (int i = 0;
                 i < tx.getIns().size();
                 i++) {
                Tx prevTx = dependency.get(new Sha256Hash(tx.getIns().get(i).getPrevTxHash()));
                if (prevTx == null) {
                    needToRequest.add(new Sha256Hash(tx.getIns().get(i).getPrevTxHash()));
                } else {
                    if (prevTx.getOuts().size() <= tx.getIns().get(i).getInSn()) {
                        valid = false;
                        break;
                    }
                    byte[] outScript = prevTx.getOuts().get(tx.getIns().get(i).getInSn())
                            .getOutScript();
                    Script pubKeyScript = new Script(outScript);
                    Script script = new Script(tx.getIns().get(i).getInSignature());
                    try {
                        script.correctlySpends(tx, i, pubKeyScript, true);
                        valid &= true;
                    } catch (ScriptException e) {
                        valid &= false;
                    }
                    if (!valid) {
                        break;
                    }
                }
            }
            try {
                tx.verify();
                valid &= true;
            } catch (VerificationException e) {
                valid &= false;
            }
            if (valid && needToRequest.size() == 0) {
                PeerManager.instance().relayedTransaction(this, tx);
                checkDependencyWith(tx);
            } else if (valid && needToRequest.size() > 0) {
                for (Sha256Hash txHash : needToRequest) {
                    if (needToRequestDependencyDict.get(txHash) == null) {
                        HashSet<Tx> txs = new HashSet<Tx>();
                        txs.add(tx);
                        needToRequestDependencyDict.put(txHash, txs);
                    } else {
                        HashSet<Tx> txs = needToRequestDependencyDict.get(txHash);
                        txs.add(tx);
                    }
                }
                sendGetDataMessageWithTxHashesAndBlockHashes(new ArrayList<Sha256Hash>
                        (needToRequest), null);
            }
        }
    }

    private void processGetData(GetDataMessage getdata) {
        log.info("{}: Received getdata message with {} items", getAddress(), getdata.getItems().size());
        ArrayList<InventoryItem> notFound = new ArrayList<InventoryItem>();
        for (InventoryItem item : getdata.getItems()) {
            if (item.type == InventoryItem.Type.Transaction) {
                Tx tx = PeerManager.instance().requestedTransaction(this, item.hash);
                if (tx != null) {
                    sendMessage(tx);
                    log.info("Peer {} asked for tx: {} , found {}", getPeerAddress()
                            .getHostAddress(), Utils.hashToString(item.hash),
                            "hash: " + Utils.hashToString(tx.getTxHash()) + ", " +
                                    "content: " + Utils.bytesToHexString(tx.bitcoinSerialize()));
                    continue;
                }else{
                    log.info("Peer {} asked for tx: {} , not found", getPeerAddress().getHostAddress(), Utils.hashToString(item.hash));
                }
            }
            notFound.add(item);
        }
        if (notFound.size() > 0) {
            sendMessage(new NotFoundMessage(notFound));
        }
    }

    private void processHeaders(HeadersMessage m) throws ProtocolException {
        // Runs in network loop thread for this peer.
        //
        // This method can run if a peer just randomly sends us a "headers" message (should never
        // happen), or more
        // likely when we've requested them as part of chain download using fast catchup. We need
        // to add each block to
        // the chain if it pre-dates the fast catchup time. If we go past it,
        // we can stop processing the headers and
        // request the full blocks from that point on instead.
        log.info("peer[{}:{}] receive {} headers", this.peerAddress.getHostAddress(),
                this.peerPort, m.getBlockHeaders().size());
        if (BlockChain.getInstance() == null) {
            // Can happen if we are receiving unrequested data, or due to programmer error.
            log.warn("Received headers when Peer is not configured with a chain.");
            return;
        }

        if (m.getBlockHeaders() == null || m.getBlockHeaders().size() == 0) {
            return;
        }

        try {
            int lastBlockTime = 0;
            byte[] firstHash = m.getBlockHeaders().get(0).getBlock().getBlockHash();
            byte[] lastHash = m.getBlockHeaders().get(m.getBlockHeaders().size() - 1).getBlock()
                    .getBlockHash();
            ArrayList<Block> blocksToRelay = new ArrayList<Block>();
            for (int i = 0;
                 i < m.getBlockHeaders().size();
                 i++) {
                BlockMessage header = m.getBlockHeaders().get(i);
                // Process headers until we pass the fast catchup time,
                // or are about to catch up with the head
                // of the chain - always process the last block as a full/filtered block to kick
                // us out of the
                // fast catchup mode (in which we ignore new blocks).

                boolean passedTime = header.getBlock().getBlockTime() >= PeerManager.instance()
                        .earliestKeyTime;
                boolean reachedTop = PeerManager.instance().getLastBlockHeight() >= this
                        .lastBlockHeight;
                if (!passedTime && !reachedTop) {
                    if (header.getBlock().getBlockTime() > lastBlockTime) {
                        lastBlockTime = header.getBlock().getBlockTime();
                        if (lastBlockTime + 7 * 24 * 60 * 60 >= PeerManager.instance()
                                .earliestKeyTime - 2 * 60 * 60) {
                            lastHash = header.getBlock().getBlockHash();
                        }
                    }
                    if(!blocksToRelay.contains(header.getBlock())){
                        blocksToRelay.add(header.getBlock());
                    }
                }
            }
            PeerManager.instance().relayedBlockHeadersForMainChain(this, blocksToRelay);
            if (lastBlockTime + 7 * 24 * 60 * 60 >= PeerManager.instance().earliestKeyTime - 2 *
                    60 * 60) {
                sendGetBlocksMessage(Arrays.asList(new byte[][]{lastHash, firstHash}), null);
            } else {
                sendGetHeadersMessage(Arrays.asList(new byte[][]{lastHash, firstHash}), null);
            }
        } catch (VerificationException e) {
            log.warn("Block header verification failed", e);
        }
    }

    private void processVersionMessage(VersionMessage version) {
        this.versionMessage = version;
        this.version = version.clientVersion;
        if (this.version < BitherjSettings.MIN_PROTO_VERSION) {
            close();
            return;
        }
        peerServices = version.localServices;
        peerTimestamp = (int) version.time;
        userAgent = version.subVer;

        lastBlockHeight = version.bestHeight;

        sendMessage(new VersionAck());
    }
    
    public boolean getDownloadData() {
        if (PeerManager.instance().getDownloadingPeer() != null) {
            return equals(PeerManager.instance().getDownloadingPeer());
        } else {
            return false;
        }
    }

    private void processPong(PongMessage m) {
        // Iterates over a snapshot of the list, so we can run unlocked here.
        if (m.getNonce() == nonce) {
            if (pingStartTime > 0) {
                if(pingTime > 0){
                    pingTime = (long) (pingTime * 0.5f + (new Date().getTime() - pingStartTime) * 0.5f);
                } else {
                    pingTime = new Date().getTime() - pingStartTime;
                }
                pingStartTime = 0;
            }
            LogUtil.i(Peer.class.getSimpleName(), "Peer " + getPeerAddress().getHostAddress() +" receive pong, ping time: " + pingTime);
        }
    }

    private void ping() {
        if (state != State.Connected) {
            return;
        }
        sendMessage(new PingMessage(nonce));
        pingStartTime = new Date().getTime();
    }

    private void sendVersionMessage() {
        sendMessage(new VersionMessage((int) PeerManager.instance().getLastBlockHeight(), false));
        log.info("Send version message to peer {}", getPeerAddress().getHostAddress());
        sentVerAck = true;
    }

    private void processAlert(AlertMessage m) {
        try {
            if (m.isSignatureValid()) {
                log.info("Received alert from peer {}: {}", toString(), m.getStatusBar());
            } else {
                log.warn("Received alert with invalid signature from peer {}: {}", toString(),
                        m.getStatusBar());
            }
        } catch (Throwable t) {
            // Signature checking can FAIL on Android platforms before Gingerbread apparently due
            // to bugs in their
            // Sha256Hash implementations! See issue 160 for discussion. As alerts are just
            // optional and not that
            // useful, we just swallow the error here.
            log.error("Failed to check signature: bug in platform libraries?", t);
        }
    }

    private void processReject(RejectMessage m) {
        exceptionCaught(new ProtocolException("Peer " + getPeerAddress().getHostAddress() + " " +
                "Rejected. \n" + m.toString()));
    }

    public void sendFilterLoadMessage(BloomFilter filter) {
        if (state != State.Connected) {
            return;
        }
        filterBlockCount = 0;
        log.info("Peer {} send bloom filter", getPeerAddress().getHostAddress());
        bloomFilterSent = true;
        sendMessage(filter);
    }

    public void sendMemPoolMessage() {
        if (state != State.Connected) {
            return;
        }
        sendMessage(new MemoryPoolMessage());
    }

    public void sendGetAddrMessage() {
        if (state != State.Connected) {
            return;
        }
        sendMessage(new GetAddrMessage());
    }

    public void sendInvMessageWithTxHash(Sha256Hash txHash) {
        if (state != State.Connected) {
            return;
        }
        InventoryMessage m = new InventoryMessage();
        m.addTransaction(TxProvider.getInstance().getTxDetailByTxHash(txHash.getBytes()));
        log.info("Peer {} send inv with tx {}", getPeerAddress().getHostAddress(),
                Utils.hashToString(txHash.getBytes()));
        sendMessage(m);
    }

    public void refetchBlocksFrom(Sha256Hash blockHash) {
        if (!currentBlockHashes.contains(blockHash)) {
            return;
        }
        Iterator<Sha256Hash> iterator = currentBlockHashes.iterator();
        while (iterator.hasNext()) {
            Sha256Hash hash = iterator.next();
            iterator.remove();
            if (blockHash.equals(hash)) {
                break;
            }
        }
        log.info("Peer {} refetch {} blocks from {}", getPeerAddress().getHostAddress(), currentBlockHashes.size(), Utils.hashToString(blockHash.getBytes()));
        sendGetDataMessageWithTxHashesAndBlockHashes(null, new ArrayList<Sha256Hash>(currentBlockHashes));
    }

    public void sendGetHeadersMessage(List<byte[]> locators, byte[] hashStop) {
        if (state != State.Connected) {
            return;
        }
        GetHeadersMessage m = new GetHeadersMessage(locators, hashStop == null ? Sha256Hash
                .ZERO_HASH.getBytes() : hashStop);
        log.info("Peer {} send get header message", getPeerAddress().getHostAddress());
        sendMessage(m);
    }

    public void sendGetHeadersMessage(List<Sha256Hash> locators, Sha256Hash hashStop) {
        ArrayList<byte[]> ls = new ArrayList<byte[]>();
        for (Sha256Hash i : locators) {
            ls.add(i.getBytes());
        }
        sendGetHeadersMessage(ls, hashStop == null ? null : hashStop.getBytes());
    }

    public void sendGetBlocksMessage(List<byte[]> locators, byte[] hashStop) {
        if (state != State.Connected) {
            return;
        }
        GetBlocksMessage m = new GetBlocksMessage(locators, hashStop == null ? Sha256Hash
                .ZERO_HASH.getBytes() : hashStop);
        log.info("Peer {} send get blocks message", getPeerAddress().getHostAddress());
        sendMessage(m);
    }

    public void sendGetBlocksMessage(List<Sha256Hash> locators, Sha256Hash hashStop) {
        ArrayList<byte[]> ls = new ArrayList<byte[]>();
        for (Sha256Hash i : locators) {
            ls.add(i.getBytes());
        }
        sendGetBlocksMessage(ls, hashStop == null ? null : hashStop.getBytes());
    }

    public void sendGetDataMessageWithTxHashesAndBlockHashes(List<Sha256Hash> txHashes,
                                                             List<Sha256Hash> blockHashes) {
        if (state != State.Connected) {
            return;
        }
        GetDataMessage m = new GetDataMessage();
        if (blockHashes != null) {
            for (Sha256Hash hash : blockHashes) {
                m.addFilteredBlock(hash.getBytes());
            }
            requestedBlockHashes.addAll(blockHashes);
        }
        if (txHashes != null) {
            for (Sha256Hash hash : txHashes) {
                m.addTransaction(hash.getBytes());
            }
        }
        int blochHashCount = 0;
        if (blockHashes != null) {
            blochHashCount = blockHashes.size();
        }

        if (filterBlockCount + blochHashCount > BitherjSettings.BLOCK_DIFFICULTY_INTERVAL) {
            log.info("{} rebuilding bloom filter after {} blocks",
                    getPeerAddress().getHostAddress(), filterBlockCount);
            sendFilterLoadMessage(PeerManager.instance().bloomFilterForPeer(this));
        }

        filterBlockCount += blochHashCount;
        log.info("Peer {} send get data message with {} tx and & {} block",
                getPeerAddress().getHostAddress(), txHashes == null ? 0 : txHashes.size(),
                blochHashCount);
        sendMessage(m);
    }


    public void connectFail() {
        PeerProvider.getInstance().conncetFail(getPeerAddress());
    }

    public void connectError() {
        PeerProvider.getInstance().removePeer(getPeerAddress());
    }


    public void connectSucceed() {
        peerConnectedCnt = 1;
        peerTimestamp = (int) (new Date().getTime() / 1000);
        PeerProvider.getInstance().connectSucceed(getPeerAddress());
        sendFilterLoadMessage(PeerManager.instance().bloomFilterForPeer(this));
    }

    @Override
    public void connectionClosed() {
        state = State.Disconnected;
        PeerManager.instance().peerDisconnected(this, DisconnectReason.Normal);
    }

    @Override
    protected void timeoutOccurred() {
        PeerManager.instance().peerDisconnected(this, DisconnectReason.Timeout);
        super.timeoutOccurred();
    }

    @Override
    protected void exceptionCaught(Exception e) {
        super.exceptionCaught(e);
        if (e instanceof ProtocolException) {
            PeerManager.instance().peerDisconnected(this, DisconnectReason.NoneProtocol);
        } else {
            PeerManager.instance().peerDisconnected(this, DisconnectReason.NoneProtocol);
        }
    }

    @Override
    public void connectionOpened() {
        if (state == State.Disconnected) {
            disconnect();
            return;
        }
        sendVersionMessage();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Peer) {
            Peer item = (Peer) o;
            return Arrays.equals(getPeerAddress().getAddress(), item.getPeerAddress().getAddress());
        } else {
            return false;
        }
    }

    public InetAddress getPeerAddress() {
        return peerAddress;
    }

    public void setPeerAddress(InetAddress peerAddress) {
        this.peerAddress = peerAddress;
    }

    public int getPeerTimestamp() {
        return peerTimestamp;
    }

    public void setPeerTimestamp(int peerTimestamp) {
        this.peerTimestamp = peerTimestamp;
    }

    public int getPeerPort() {
        return peerPort;
    }

    public void setPeerPort(int peerPort) {
        this.peerPort = peerPort;
    }

    public long getPeerServices() {
        return peerServices;
    }

    public void setPeerServices(long peerServices) {
        this.peerServices = peerServices;
    }

    public int getPeerConnectedCnt() {
        return peerConnectedCnt;
    }

    public void setPeerConnectedCnt(int peerConnectedCnt) {
        this.peerConnectedCnt = peerConnectedCnt;
    }

    public long getLastBlockHeight() {
        return lastBlockHeight;
    }

    public int getClientVersion(){
        return version;
    }

    public String getSubVersion(){
        return userAgent;
    }
}