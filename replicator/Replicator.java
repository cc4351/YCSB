package rubblejava;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Replicator {
    
    private final int ycsb_port;
    private final Server ycsb_server;

    /* constructor */
    public Replicator(int ycsb_p, String head_addr, String tail_addr) {
        this.ycsb_port = ycsb_p;
        this.ycsb_server = ServerBuilder.forPort(ycsb_p)
                    .executor(Executors.newFixedThreadPool(64))
                    .addService(new ReplicatorService(head_addr, tail_addr))
                    .build();
    }

    public void start() throws IOException {
        this.ycsb_server.start();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down Replicator since JVM is shutting down");
                try {
                    Replicator.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** Replicator shut down");
            }
        });
    }

    public void stop() throws InterruptedException {
        if (this.ycsb_server != null) {
            this.ycsb_server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
    * Await termination on the main thread since the grpc library uses daemon threads.
    */
    private void blockUntilShutdown() throws InterruptedException {
        if (this.ycsb_server != null) {
            this.ycsb_server.awaitTermination();
        }
    }
    

    public static void main(String[] args) throws Exception {
        // Replicator replicator = new Replicator(50050, "128.110.153.102:50051", "128.110.153.93:50052");
        Replicator replicator = new Replicator(50050, "128.110.153.93:50052", "128.110.153.93:50052");
        replicator.start();
        replicator.blockUntilShutdown();
    }

    //TODO: find a better way than round-robin to send replies back to clients
    private static class ReplicatorService extends RubbleKvStoreServiceGrpc.RubbleKvStoreServiceImplBase {
        private static ConcurrentHashMap<Long, StreamObserver<Op>> tail_obs;
        private static ConcurrentHashMap<Long, StreamObserver<Op>> head_obs;
        private static ConcurrentHashMap<Long, StreamObserver<OpReply>> ycsb_obs;
        private static StreamObserver<OpReply> ycsb_ob;
        private final List<RubbleKvStoreServiceGrpc.RubbleKvStoreServiceStub> tailStub;
        private final List<ManagedChannel> tailChan;
        private final List<RubbleKvStoreServiceGrpc.RubbleKvStoreServiceStub> headStub;
        private final List<ManagedChannel> headChan;
        private String head_addr, tail_addr;
    
        public ReplicatorService(String head_addr, String tail_addr) {   
            tail_obs = new ConcurrentHashMap<>();
            head_obs = new ConcurrentHashMap<>();
            ycsb_obs = new ConcurrentHashMap<>();
            System.out.println("replicator start service with head: " + head_addr + " tail: " + tail_addr);
            // create channels --> default to 16 channels
            this.tailChan = new ArrayList<>();
            this.tailStub = new ArrayList<>();
            this.headChan = new ArrayList<>();
            this.headStub = new ArrayList<>();
            this.head_addr = head_addr;
            this.tail_addr = tail_addr;
            allocChannel(tail_addr, 16, this.tailChan, this.tailStub);
            allocChannel(head_addr, 16, this.headChan, this.headStub);
        }
        
        // helper function to pre-allocate channels to communicate with shard heads and tails
        private void allocChannel(String addr, int num_chan, List<ManagedChannel> chanList,
                    List<RubbleKvStoreServiceGrpc.RubbleKvStoreServiceStub>stubList) {
            for(int i = 0; i < num_chan; i++) {
                ManagedChannel chan = ManagedChannelBuilder.forTarget(addr).usePlaintext().build();
                chanList.add(chan);
                stubList.add(RubbleKvStoreServiceGrpc.newStub(chan));
            }
        }

        @Override
        public StreamObserver<Op> doOp(final StreamObserver<OpReply> ob) {
            // add the write-back-to-ycsb stream to Map
            // this.ycsb_obs.put(Thread.currentThread().getId(), ob);
             
            final Long tid = Thread.currentThread().getId();
            System.out.println("thread: " + tid + " in doOp");

            // create tail client that will use the write-back-ycsb stream
            // note that we create one tail observer per thread
            // this.ycsb_obs.put(tid, ob);
            this.ycsb_ob = ob;
            initTailOb(tid);
            initHeadOb(tid);
            final ConcurrentHashMap<Long, StreamObserver<OpReply>> dup = this.ycsb_obs;
            System.out.println("dup: " + dup.mappingCount());
            // create the ycsb-op processing logic with the tail client
            // System.out.println("tail_client: " + this.obs.containsKey(tid));
            final StreamObserver<Op> tail_client = this.tail_obs.get(tid);
            final StreamObserver<Op> head_client = this.head_obs.get(tid);
            return new StreamObserver<Op>(){
                int opcount = 0;
                // Set set = new HashSet();
                @Override
                public void onNext(Op op) {
                    // GET --> go to TAIL
                    // if (op.getOpsCount() > 0 && op.getOps(0).getType().getNumber() == 0) {
                    dup.put(op.getId(), ob);
                    if (op.getType().getNumber() == 0) {
                        // System.out.println("tid: " + tid + " Thread " + op.getId() + " onNext -> tail");
                        tail_client.onNext(op);
                    } else { // other ops going to HEAD
                        // System.out.println("tid: " + tid + " Thread " + Thread.currentThread().getId() + " onNext -> head");
                        head_client.onNext(op);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // System.err.println("ycsb observer failed: " + Status.fromThrowable(t));
                }

                @Override
                public void onCompleted() {
                    System.out.println("ycsb incoming stream completed");
                }
            };  
        }

        @Override
        public StreamObserver<OpReply> sendReply(final StreamObserver<Reply> ob) {
            final StreamObserver<OpReply> ycsb_tmp = this.ycsb_ob;
            final ConcurrentHashMap<Long, StreamObserver<OpReply>> ycsb_tmps = this.ycsb_obs;
            System.out.println("tmps: " + ycsb_tmps.mappingCount());
            return new StreamObserver<OpReply>(){
                int opcount = 0;
                @Override
                public void onNext(OpReply op) {
                    ++opcount;
                    if(opcount % 100000 == 0) {
                        System.out.println("op: " + op.getType() + " key" + op.getKey() + " id: " + op.getId());
                    }
                    // System.out.println("SendReply forward to ycsb, ycsb is alive: " + !(ycsb_tmp == null));
                    ycsb_tmps.get(op.getId()).onNext(op);
                    // System.out.println("opcount: " + (++opcount));
                }

                @Override
                public void onError(Throwable t) {
                    // System.err.println("SendReply ob failed: " + Status.fromThrowable(t));
                    // ycsb_tmp.onCompleted();
                }

                @Override
                public void onCompleted() {
                    System.out.println("sendReply ob completed");
                    // ycsb_tmp.onCompleted();
                }
            };  
            

        }
        
        // add observer that could write to tail into Map<StreamObserver> obs
        private void initTailOb(Long id) {
            // replies from tail node
            this.tail_obs.put(id, this.tailStub.get(id.intValue()%16).doOp( new StreamObserver<OpReply>(){
                @Override
                public void onNext(OpReply reply) {
                    System.out.println("reply from tail ob");
                }

                @Override
                public void onError(Throwable t) {
                    // System.err.println("tail observer failed: " + Status.fromThrowable(t));
                }

                @Override
                public void onCompleted() {
                    System.out.println("tail node reply stream completed");
                }
            }));
        }

        // add an observer to comm with head nodes into Map<long, StreamObserver<Op>> head_obs
        private void initHeadOb(Long id) {
            // replies from tail node
            this.head_obs.put(id, this.headStub.get(id.intValue()%16).doOp( new StreamObserver<OpReply>(){
                @Override
                public void onNext(OpReply reply) {
                    // do nothing on replies from primary  
                }

                @Override
                public void onError(Throwable t) {
                    // System.err.println("head observer failed: " + Status.fromThrowable(t));
                }

                @Override
                public void onCompleted() {
                    System.out.println("head node reply stream completed");
                }
            }));
        }       
    }
        
}

       


    

    