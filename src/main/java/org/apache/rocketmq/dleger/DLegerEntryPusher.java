package org.apache.rocketmq.dleger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.dleger.entry.DLegerEntry;
import org.apache.rocketmq.dleger.exception.DLegerException;
import org.apache.rocketmq.dleger.protocol.AppendEntryResponse;
import org.apache.rocketmq.dleger.protocol.DLegerResponseCode;
import org.apache.rocketmq.dleger.protocol.PushEntryRequest;
import org.apache.rocketmq.dleger.protocol.PushEntryResponse;
import org.apache.rocketmq.dleger.store.DLegerStore;
import org.apache.rocketmq.dleger.utils.Pair;
import org.apache.rocketmq.dleger.utils.PreConditions;
import org.apache.rocketmq.dleger.utils.UtilAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DLegerEntryPusher {

    private Logger logger = LoggerFactory.getLogger(DLegerEntryPusher.class);

    private DLegerConfig dLegerConfig;
    private DLegerStore dLegerStore;

    private MemberState memberState;

    private DLegerRpcService dLegerRpcService;

    private Map<String, Long> peerWaterMarks = new ConcurrentHashMap<>();
    private Map<Long, CompletableFuture<AppendEntryResponse>> pendingAppendEntryResponseMap = new ConcurrentHashMap<>();


    private EntryHandler entryHandler = new EntryHandler(logger);


    private QuorumAckChecker quorumAckChecker = new QuorumAckChecker(logger);


    private Map<String, EntryDispatcher> dispatcherMap = new HashMap<>();

    public DLegerEntryPusher(DLegerConfig dLegerConfig, MemberState memberState, DLegerStore dLegerStore, DLegerRpcService dLegerRpcService) {
        this.dLegerConfig = dLegerConfig;
        this.memberState =  memberState;
        this.dLegerStore = dLegerStore;
        this.dLegerRpcService = dLegerRpcService;
        for (String peer: memberState.getPeerMap().keySet()) {
            if (!peer.equals(memberState.getSelfId())) {
                dispatcherMap.put(peer, new EntryDispatcher(peer, logger));
            }
        }
    }


    public void startup() {
        entryHandler.start();
        quorumAckChecker.start();
        for (String peerId: memberState.getPeerMap().keySet()) {
            peerWaterMarks.put(peerId, -1L);
        }
        for (EntryDispatcher dispatcher: dispatcherMap.values()) {
            dispatcher.start();
        }
    }

    public void shutdown() {
        entryHandler.shutdown();
        quorumAckChecker.shutdown();
        for (EntryDispatcher dispatcher: dispatcherMap.values()) {
            dispatcher.shutdown();
        }
    }

    public CompletableFuture<PushEntryResponse> handlePush(PushEntryRequest request) throws Exception {
        return entryHandler.handlePush(request);
    }


    private void updatePeerWaterMark(String peerId, long index) {
        synchronized (peerWaterMarks) {
            if (peerWaterMarks.get(peerId) < index) {
                peerWaterMarks.put(peerId, index);
            }
        }
    }

    public void waitAck(Long index, CompletableFuture<AppendEntryResponse> future) {
        updatePeerWaterMark(memberState.getLeaderId(), index);
        if (memberState.getPeerMap().size() == 1) {
            AppendEntryResponse response = new AppendEntryResponse();
            response.setIndex(index);
            future.complete(response);
        }  else {
            pendingAppendEntryResponseMap.put(index, future);
            wakeUpDispatchers();
        }
    }

    public void wakeUpDispatchers() {
        for (EntryDispatcher dispatcher: dispatcherMap.values()) {
            dispatcher.wakeup();
        }
    }


    private class QuorumAckChecker extends ShutdownAbleThread {

        public QuorumAckChecker(Logger logger) {
            super("QuorumAckChecker", logger);
        }

        @Override
        public void doWork() {
                try {
                    if (!memberState.isLeader()) {
                        waitForRunning(1);
                        return;
                    }
                    long quorumIndex = -1;
                    for (Long index: peerWaterMarks.values()) {
                        int num = 0;
                        for (Long another: peerWaterMarks.values()) {
                            if (another >= index) {
                                num++;
                            }
                        }
                        if (memberState.isQuorum(num)) {
                            quorumIndex = index;
                            break;
                        }
                    }
                    if (quorumIndex == -1) {
                        waitForRunning(1);
                        return;
                    }
                    for (Long i = quorumIndex; i >= 0 ; i--) {
                        CompletableFuture<AppendEntryResponse> future = pendingAppendEntryResponseMap.remove(i);
                        if (future != null) {
                            AppendEntryResponse response = new AppendEntryResponse();
                            response.setIndex(i);
                            future.complete(response);
                        } else {
                            break;
                        }
                    }
                } catch (Throwable t) {
                    DLegerEntryPusher.this.logger.error("Error in {}", getName(), t);
                    UtilAll.sleep(100);
                }
        }
    }

    private class EntryDispatcher extends ShutdownAbleThread {

        private AtomicReference<PushEntryRequest.Type> type = new AtomicReference<>(PushEntryRequest.Type.COMPARE);
        private String peerId;
        private long compareIndex = -1;
        private long writeIndex = -1;
        private int maxPendingSize = 100;
        private ConcurrentMap<Long, CompletableFuture<PushEntryResponse>> pendingMap = new ConcurrentHashMap<>();

        public EntryDispatcher(String peerId, Logger logger) {
            super("EntryDispatcher-" + memberState.getSelfId() + "-" + peerId, logger);
            this.peerId = peerId;
        }

        private void doWrite() throws Exception {
            while (true) {
                if (!memberState.isLeader()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.WRITE) {
                    break;
                }
                if (writeIndex > dLegerStore.getLegerEndIndex()) {
                    break;
                }
                if (pendingMap.size() >= maxPendingSize) {
                    break;
                }
                DLegerEntry entry = dLegerStore.get(writeIndex);
                PreConditions.check(entry != null, DLegerResponseCode.INTERNAL_ERROR, "writeIndex=%d", writeIndex);
                PushEntryRequest request = new PushEntryRequest();
                request.setRemoteId(peerId);
                request.setLeaderId(memberState.getSelfId());
                request.setTerm(memberState.currTerm());
                request.setEntry(entry);
                CompletableFuture<PushEntryResponse> responseFuture = dLegerRpcService.push(request);
                pendingMap.put(writeIndex, responseFuture);
                responseFuture.whenComplete((x, ex) -> {
                    if (x.getCode() == DLegerResponseCode.SUCCESS.getCode()) {
                        pendingMap.remove(x.getIndex());
                        updatePeerWaterMark(peerId, x.getIndex());
                        quorumAckChecker.wakeup();
                    } else if (x.getCode() == DLegerResponseCode.INCONSISTENT_STATE.getCode()) {
                        logger.info("Get INCONSISTENT_STATE when push to {} at {}", peerId, x.getIndex());
                        if(this.type.compareAndSet(PushEntryRequest.Type.WRITE, PushEntryRequest.Type.COMPARE)) {
                            compareIndex = dLegerStore.getLegerEndIndex();
                            pendingMap.clear();
                        }
                    } else {
                        //TODO should redispatch
                        logger.info("Unexpected response in entry dispatcher {} ", x);
                    }
                });
                writeIndex++;
            }
        }

        private void doTruncate(long truncateIndex) throws Exception {
            DLegerEntry truncateEntry = dLegerStore.get(truncateIndex);
            PreConditions.check(truncateEntry != null, DLegerResponseCode.UNKNOWN);
            logger.info("Will push data to truncate {} truncateIndex={} pos={}", peerId, truncateIndex, truncateEntry.getPos());
            PushEntryRequest truncateRequest = new PushEntryRequest();
            truncateRequest.setRemoteId(peerId);
            truncateRequest.setLeaderId(memberState.getSelfId());
            truncateRequest.setTerm(memberState.currTerm());
            truncateRequest.setEntry(truncateEntry);
            truncateRequest.setType(PushEntryRequest.Type.TRUNCATE);
            PushEntryResponse truncateResponse = dLegerRpcService.push(truncateRequest).get(3, TimeUnit.SECONDS);
            PreConditions.check(truncateResponse != null, DLegerResponseCode.UNKNOWN, null);
            PreConditions.check(truncateResponse.getCode() == DLegerResponseCode.SUCCESS.getCode(), DLegerResponseCode.UNKNOWN, null);
            changeToWriteState(truncateIndex);
        }

        private void changeToWriteState(long truncateIndex) {
            updatePeerWaterMark(peerId, truncateIndex);
            quorumAckChecker.wakeup();
            writeIndex =  truncateIndex + 1;
            type.set(PushEntryRequest.Type.WRITE);
        }

        private void doCompare() throws Exception {
            while (true) {
                if (!memberState.isLeader()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.COMPARE) {
                    break;
                }
                if (compareIndex < 0) {
                    compareIndex = dLegerStore.getLegerEndIndex();
                    break;
                }
                if (compareIndex > dLegerStore.getLegerEndIndex() || compareIndex < dLegerStore.getLegerBeginIndex()) {
                    logger.info("[DoCompare] compareIndex {} out of range {}-{}", compareIndex, dLegerStore.getLegerBeginIndex(), dLegerStore.getLegerEndIndex());
                    compareIndex = dLegerStore.getLegerEndIndex();
                    break;
                }
                DLegerEntry entry = dLegerStore.get(compareIndex);
                PreConditions.check(entry != null, DLegerResponseCode.INTERNAL_ERROR, "compareIndex=%d", compareIndex);
                PushEntryRequest request = new PushEntryRequest();
                request.setRemoteId(peerId);
                request.setLeaderId(memberState.getSelfId());
                request.setTerm(memberState.currTerm());
                request.setEntry(entry);
                request.setType(PushEntryRequest.Type.COMPARE);
                CompletableFuture<PushEntryResponse> responseFuture = dLegerRpcService.push(request);
                PushEntryResponse response = responseFuture.get(3, TimeUnit.SECONDS);
                PreConditions.check(response != null, DLegerResponseCode.INTERNAL_ERROR, "compareIndex=%d", compareIndex);
                PreConditions.check(response.getCode() == DLegerResponseCode.INCONSISTENT_STATE.getCode() || response.getCode() == DLegerResponseCode.SUCCESS.getCode()
                    , DLegerResponseCode.valueOf(response.getCode()), "compareIndex=%d", compareIndex);
                long truncateIndex = -1;
                if (response.getCode() == DLegerResponseCode.SUCCESS.getCode()) {
                    if (compareIndex == response.getEndIndex()) {
                        changeToWriteState(compareIndex);
                        break;
                    } else {
                        truncateIndex = compareIndex;
                    }
                } else if (response.getEndIndex() < dLegerStore.getLegerBeginIndex()
                    || response.getBeginIndex() > dLegerStore.getLegerEndIndex()) {
                    truncateIndex = dLegerStore.getLegerBeginIndex();
                } else if(compareIndex < response.getBeginIndex()) {
                    truncateIndex = dLegerStore.getLegerBeginIndex();
                } else if (compareIndex > response.getEndIndex()){
                    compareIndex =  response.getEndIndex();
                } else {
                    compareIndex--;
                }
                if (compareIndex < dLegerStore.getLegerBeginIndex()) {
                    truncateIndex = dLegerStore.getLegerBeginIndex();
                }
                if (truncateIndex != -1) {
                    compareIndex = dLegerStore.getLegerEndIndex();
                    doTruncate(truncateIndex);
                    break;
                }
            }
        }

        @Override
        public void doWork() {
            try {
                if (!memberState.isLeader()) {
                    waitForRunning(1);
                    return;
                }
                if (type.get() == PushEntryRequest.Type.WRITE) {
                    doWrite();
                } else {
                    doCompare();
                }
                waitForRunning(1);
            } catch (Throwable t) {
                DLegerEntryPusher.this.logger.error("Error in {} writeIndex={} compareIndex={}", getName(), writeIndex, compareIndex, t);
                UtilAll.sleep(100);
            }
        }
    }

    private class EntryHandler extends ShutdownAbleThread {


        ConcurrentMap<Long, Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>> writeRequestMap = new ConcurrentHashMap<>();
        BlockingQueue<Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>> compareOrTruncateRequests = new ArrayBlockingQueue<Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>>(100);

        public EntryHandler(Logger logger) {
            super("EntryHandler", logger);
        }

        public CompletableFuture<PushEntryResponse>  handlePush(PushEntryRequest request) throws Exception {
            CompletableFuture<PushEntryResponse> future = new CompletableFuture<>();
            long index = request.getEntry().getIndex();
            switch (request.getType()) {
                case WRITE:
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> old = writeRequestMap.putIfAbsent(index, new Pair<>(request, future));
                    if (old != null) {
                        logger.warn("[MONITOR]The index {} has already existed with {} and curr is {}", index, old.getKey().baseInfo(), request.baseInfo());
                        return CompletableFuture.completedFuture(buildResponse(request, DLegerResponseCode.REPEATED_PUSH.getCode()));
                    } else {
                        return future;
                    }
                case COMPARE:
                case TRUNCATE:
                    writeRequestMap.clear();
                    compareOrTruncateRequests.put(new Pair<>(request, future));
                    return future;
                default:
                    logger.error("[BUG]Unknown type {} at {} from {}", request.getType(), index, request.baseInfo());
                    future.complete(buildResponse(request, DLegerResponseCode.ILLEGAL_ARGUMENT.getCode()));
                    return future;
            }
        }



        private PushEntryResponse buildResponse(PushEntryRequest request, int code) {
            PushEntryResponse response = new PushEntryResponse();
            response.setCode(code);
            response.setTerm(request.getTerm());
            response.setIndex(request.getEntry().getIndex());
            response.setBeginIndex(dLegerStore.getLegerBeginIndex());
            response.setEndIndex(dLegerStore.getLegerEndIndex());
            return response;
        }

        private void handleDoWrite(long writeIndex, PushEntryRequest request, CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(writeIndex == request.getEntry().getIndex(), DLegerResponseCode.INCONSISTENT_STATE);
                long index = dLegerStore.appendAsFollower(request.getEntry(), request.getTerm(), request.getLeaderId());
                PreConditions.check(index == writeIndex, DLegerResponseCode.INCONSISTENT_STATE);
                PushEntryResponse response = new PushEntryResponse();
                response.setTerm(request.getTerm());
                response.setIndex(index);
                future.complete(response);
            } catch (Throwable t) {
                logger.error("[HandleDoWrite] writeIndex={}", writeIndex, t);
                future.complete(buildResponse(request, DLegerResponseCode.INCONSISTENT_STATE.getCode()));
            }
        }

        private CompletableFuture<PushEntryResponse> handleDoCompare(long compareIndex, PushEntryRequest request, CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(compareIndex == request.getEntry().getIndex(), DLegerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.COMPARE, DLegerResponseCode.UNKNOWN);
                DLegerEntry local = dLegerStore.get(compareIndex);
                PreConditions.check(request.getEntry().equals(local), DLegerResponseCode.INCONSISTENT_STATE);
                PushEntryResponse response = new PushEntryResponse();
                response.setTerm(request.getTerm());
                response.setIndex(compareIndex);
                response.setBeginIndex(dLegerStore.getLegerBeginIndex());
                response.setEndIndex(dLegerStore.getLegerEndIndex());
                future.complete(response);
            } catch (Throwable t) {
                logger.error("[HandleDoCompare] compareIndex={}", compareIndex, t);
                future.complete(buildResponse(request, DLegerResponseCode.INCONSISTENT_STATE.getCode()));
            }
            return future;
        }

        private CompletableFuture<PushEntryResponse> handleDoTruncate(long truncateIndex, PushEntryRequest request, CompletableFuture<PushEntryResponse> future) {
            try {
                logger.info("[HandleDoTruncate] truncateIndex={} pos={}", truncateIndex, request.getEntry().getPos());
                PreConditions.check(truncateIndex == request.getEntry().getIndex(), DLegerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.TRUNCATE, DLegerResponseCode.UNKNOWN);

                long index = dLegerStore.truncate(request.getEntry(), request.getTerm(), request.getLeaderId());
                PreConditions.check(index == truncateIndex, DLegerResponseCode.INCONSISTENT_STATE);
                PushEntryResponse response = new PushEntryResponse();
                response.setTerm(request.getTerm());
                response.setIndex(index);
                future.complete(response);
            } catch (Throwable t) {
                logger.error("[HandleDoTruncate] truncateIndex={}", truncateIndex, t);
                future.complete(buildResponse(request, DLegerResponseCode.INCONSISTENT_STATE.getCode()));
            }
            return future;
        }

        @Override
        public void doWork() {
            try {
                if (!memberState.isFollower()) {
                    waitForRunning(1);
                    return;
                }
                if (compareOrTruncateRequests.peek() != null) {
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair  = compareOrTruncateRequests.poll();
                    PreConditions.check(pair != null, DLegerResponseCode.UNKNOWN);
                    if (pair.getKey().getType() == PushEntryRequest.Type.TRUNCATE) {
                        handleDoTruncate(pair.getKey().getEntry().getIndex(), pair.getKey(), pair.getValue());
                    } else {
                        handleDoCompare(pair.getKey().getEntry().getIndex(), pair.getKey(), pair.getValue());
                    }
                } else {
                    long nextIndex = dLegerStore.getLegerEndIndex() + 1;
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair  = writeRequestMap.remove(nextIndex);
                    if (pair == null) {
                        waitForRunning(1);
                        return;
                    }
                    PushEntryRequest request = pair.getKey();
                    handleDoWrite(nextIndex, request, pair.getValue());
                }
            } catch (Throwable t) {
                DLegerEntryPusher.this.logger.error("Error in {}", getName(),  t);
                UtilAll.sleep(100);
            }
        }
    }
}
