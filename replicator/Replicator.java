package rubblejava;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.lang.System;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.math.BigInteger;
import org.yaml.snakeyaml.Yaml;


public class Replicator {
    
    private final int ycsb_port;
    private final Server ycsb_server;

    /* constructor */
    public Replicator(int ycsb_p, String[][] shards, int batch_size, int chan_num) {
      this.ycsb_port = ycsb_p;
      this.ycsb_server = ServerBuilder.forPort(ycsb_p)
                    .executor(Executors.newFixedThreadPool(16))
                    .addService(new ReplicatorService(shards, batch_size, chan_num))
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
      System.out.print("Reading configuration file...");
      Yaml yaml = new Yaml();
      InputStream inputStream = new FileInputStream("config/test.yml");
      Map<String, Object> obj = yaml.load(inputStream);
      System.out.println("Finished");
      LinkedHashMap<String, Object> rubble_params = (LinkedHashMap<String, Object>)obj.get("rubble_params");
      LinkedHashMap<String, String> ip_map = (LinkedHashMap<String, String>)obj.get("ip_map");
      ArrayList<LinkedHashMap<String, Object>> shard_ports = (ArrayList<LinkedHashMap<String, Object>>)rubble_params.get("shard_info");
      int num_shards = (int)rubble_params.get("shard_num");
      int num_replica = (int)rubble_params.get("replica_num");
      int batch_size = (int)rubble_params.getOrDefault("batch_size", 1);
      int chan_num = (int) rubble_params.getOrDefault("chan_num", 1);
	    int self_port = (int) rubble_params.getOrDefault("replicator_port", 50050);
      System.out.println("Shard number: "+num_shards);
      System.out.println("Replica number(chain length): "+num_replica);
      System.out.println("Batch size: "+batch_size);
      System.out.println("Number of channels: " + chan_num);
        
      String[][] shards = new String[num_shards][2];
      int shard_ind = 0;

      for (LinkedHashMap<String, Object> shard_tag: shard_ports) {
        ArrayList<Object> ports = (ArrayList<Object>) shard_tag.get("sequence");
        LinkedHashMap<String, String> head_pair = (LinkedHashMap<String, String>) ports.get(0);
        LinkedHashMap<String, String> tail_pair = (LinkedHashMap<String, String>) ports.get(ports.size()-1);
        String head_port = ip_map.get(head_pair.get("ip")) + ":" + String.valueOf(head_pair.get("port"));
        String tail_port = ip_map.get(tail_pair.get("ip")) + ":" + String.valueOf(tail_pair.get("port"));
        System.out.println("Head: "+head_port);
        System.out.println("Tail: "+tail_port);
        shards[shard_ind] = new String[]{head_port, tail_port};
        shard_ind++;
      }
      Replicator replicator = new Replicator(self_port, shards, batch_size, chan_num);
      replicator.start();
      replicator.blockUntilShutdown();
    }

    private static class ReplicatorService extends RubbleKvStoreServiceGrpc.RubbleKvStoreServiceImplBase {
      private static ConcurrentHashMap<Long, StreamObserver<OpReply>> ycsb_obs;
      private final List<ManagedChannel> tailChan;
      private final List<ManagedChannel> headChan;
      private int num_shards, num_channels, batch_size;
      private static ConcurrentHashMap<StreamObserver<OpReply>, OpReply.Builder> replyBuilders;
      private static ConcurrentHashMap<Long, AtomicLong> replyCounts;
      private static ConcurrentHashMap<Long, SingleOpReply[]> buffers;
      private static final Logger LOGGER = Logger.getLogger(ReplicatorService.class.getName());
    
      public ReplicatorService(String[][] shards, int batch_size, int chan_num) {   
          ycsb_obs = new ConcurrentHashMap<>();
          this.tailChan = new ArrayList<>();
          this.headChan = new ArrayList<>();
          this.batch_size = batch_size;
          this.num_channels = chan_num;
          this.replyBuilders = new ConcurrentHashMap<>();
          this.replyCounts = new ConcurrentHashMap<>();
          setupShards(shards, this.num_channels);
          
      }

      private void setupShards(String[][] shards, int num_chan) {
        this.num_shards = shards.length;
        System.out.println("Replicator sees " + this.num_shards + " shards");
        for(String[] s: shards){
          // TODO: add assert format here
          System.out.println("shard head: " + s[0] + " shard tail: " + s[1]);
          allocChannel(s[0], num_chan, this.headChan);
          allocChannel(s[1], num_chan, this.tailChan);
        }
        System.out.println("number of channels: " + this.headChan.size());
      }
        
      // helper function to pre-allocate channels to communicate with shard heads and tails
      private void allocChannel(String addr, int num_chan, List<ManagedChannel> chanList) {
        for(int i = 0; i < num_chan; i++) {
          ManagedChannel chan = ManagedChannelBuilder.forTarget(addr).usePlaintext().build();
          chanList.add(chan);
        }
      }                                                                            

      @Override
      public StreamObserver<Op> doOp(final StreamObserver<OpReply> ob) {
        // add the write-back-to-ycsb stream to Map
        
        final Long tid = Thread.currentThread().getId();
        // System.out.println("thread: " + tid + " in doOp");
            
        // create tail client that will use the write-back-ycsb stream
        // note that we create one tail observer per thread per shard
        final ConcurrentHashMap<Integer, StreamObserver<Op>> tail_clients = initTailOb(tid);
        final ConcurrentHashMap<Integer, StreamObserver<Op>> head_clients = initHeadOb(tid);
        final int batch_size = this.batch_size;
        final int mod_shard = this.num_shards;
        System.out.println("num shards: " + mod_shard);
        final BigInteger big_mod = BigInteger.valueOf(this.num_shards);

        return new StreamObserver<Op>(){
            int opcount = 0;
            // builder to cache put and get requests to each shard
            HashMap<Integer, Op.Builder> put_builder = new HashMap<>();
            HashMap<Integer, Op.Builder> get_builder = new HashMap<>();
            boolean hasInit = false;
            long ycsb_tid; 
            Op.Builder builder_;
            long start_time;
            long shard1head = 0, shard2head = 0, shard1tail = 0, shard2tail=0;
                
            private void init(Long idx) {
              // LOGGER.info("Thread idx: " + tid + " init");
              start_time = System.nanoTime();
              ycsb_obs.put(idx, ob);
              replyBuilders.put(ob, OpReply.newBuilder());
              for (int i = 0; i < mod_shard; i++) {
                put_builder.put(i, Op.newBuilder());
                get_builder.put(i, Op.newBuilder());
              }
              hasInit = true;
            }

            @Override
            public void onNext(Op op) {
              // ignore if empty op
              assert op.getOpsCount() > 0:"empty op received";
              opcount += op.getOpsCount();
              Long idx = op.getOps(0).getId();
              int mod = (idx.intValue())%mod_shard;
                
              // add the observer to map and check if overriding any other thread
              if(!hasInit) {
                this.init(idx);
                ycsb_tid = idx;
              }

              // TODO: is there a better way to do sharding than converting to BigInteger
              // i.e. bitmasking w/o converting to string or use stringbuilder
              for(SingleOp sop: op.getOpsList()){
                // sharding
                // TODO: xor first and last byte sharding
                byte[] by = sop.getKey().getBytes();
                int shard_idx = by[by.length -1]%mod_shard;

                if (sop.getType() == SingleOp.OpType.GET){ // GET
                  // System.out.println("--------GETGETGET------------------");
                  builder_ = get_builder.get(shard_idx);
                  builder_.addOps(sop);
                  if (builder_.getOpsCount() == batch_size ){
                    tail_clients.get(shard_idx).onNext(builder_.build());
                    get_builder.get(shard_idx).clear();
                    // System.out.println("GET batch to shard: " + shard_idx + " from thread: " + tid + " time: " + System.nanoTime());
                    if (shard_idx == 0) {
                      shard1tail += batch_size;
                    } else {
                      shard2tail += batch_size;
                    }
                  }
                } else { //PUT
                  // System.out.println("--------PUTPUTPUT------------------");
                  builder_ = put_builder.get(shard_idx);
                  builder_.addOps(sop);
                  if (builder_.getOpsCount() == batch_size ){
                    head_clients.get(shard_idx).onNext(builder_.build());
                    put_builder.get(shard_idx).clear();
                    // System.out.println("PUT batch to shard: " + shard_idx + " from thread: " + tid + " time: " + System.nanoTime());
                    if (shard_idx == 0) {
                      shard1head += batch_size;
                    } else {
                      shard2head += batch_size;
                    }
                  }
                }
              }
            }

            @Override
            public void onError(Throwable t) {
                // System.err.println("ycsb observer failed: " + Status.fromThrowable(t));
            }

            @Override
            public void onCompleted() {
              // send out all requests in cache
              int i = 0; 
              for (Map.Entry<Integer, Op.Builder> entry : put_builder.entrySet()) {
                i++;
                if (entry.getValue().getOpsCount() > 0) {
                  if(i == 1) {
                    shard1head += entry.getValue().getOpsCount();
                  } else {
                    shard2head += entry.getValue().getOpsCount();
                  }
                  head_clients.get(entry.getKey()).onNext(entry.getValue().build());
                  put_builder.get(entry.getKey()).clear();
                }
              }
              for (Map.Entry<Integer, Op.Builder> entry : get_builder.entrySet()) {
                i++;
                if (entry.getValue().getOpsCount() > 0) {
                  if(i == 3) {
                    shard1tail += entry.getValue().getOpsCount();
                  } else {
                    shard2tail += entry.getValue().getOpsCount();
                  }
                  tail_clients.get(entry.getKey()).onNext(entry.getValue().build());
                  put_builder.get(entry.getKey()).clear();
                }
              }
              // System.out.println("Thread: " + ycsb_tid + " put shard 0 head " + shard1head);
              // System.out.println("Thread: " + ycsb_tid + " put shard 1 head " + shard2head);
              // System.out.println("Thread: " + ycsb_tid + " put shard 0 tail " + shard1tail);
              // System.out.println("Thread: " + ycsb_tid + " put shard 1 tail " + shard2tail);
              System.out.println("Thread: " + ycsb_tid + " sum of shard 0 op: " + (shard1head + shard1tail));
              System.out.println("Thread: " + ycsb_tid + " sum of shard 1 op: " + (shard2head + shard2tail));
                // System.out.println("Thread: " + tid + " time: " + (System.nanoTime() - start_time ));
                // System.out.println( " ycsb incoming stream completed");
                
            }
        };  
      }

      @Override
      public StreamObserver<OpReply> sendReply(final StreamObserver<Reply> ob) {
        return new StreamObserver<OpReply>(){
          // int opcount = 0;
          StreamObserver<OpReply> tmp;
            
          @Override
          public void onNext(OpReply op) {
            assert op.getRepliesCount() >0;
            try {
              Long ttid = op.getReplies(0).getId();
              replyCounts.putIfAbsent(ttid, new AtomicLong());
              StreamObserver<OpReply> ycsbOb = ycsb_obs.get(ttid);
              // System.out.println("ttid: " + ttid + " got a batch: " + op.getRepliesCount());
              OpReply.Builder replyBuilder = replyBuilders.get(ycsbOb);
              for(SingleOpReply reply : op.getRepliesList()){
                Long ccount;
                if (((ccount = replyCounts.get(ttid).incrementAndGet()) % batch_size) <= 20 || ccount > batch_size - 20 ) {
                  synchronized(replyBuilder) {
                    // System.out.println("tid: " + ttid + " ccount: " + ccount);
                    replyBuilder.addReplies(reply);
                    if (ccount % batch_size == 0) {
                      ycsbOb.onNext(replyBuilder.build());
                      if (ccount % 100000 == 0) {
                        System.out.println("tid: " + ttid + " delivered at " + ccount + " with size " + replyBuilder.getRepliesCount());
                      }
                      replyBuilder.clear();
                    }
                  }
                } else {
                  replyBuilder.addReplies(reply);
                }
              }
              
            } catch (Exception e) {
              e.printStackTrace();
              System.out.println("error connecting to ycsb tmp ob " + op.getReplies(0).getId());
              System.out.println("first key: " + op.getReplies(0).getKey());
            }
          }

          @Override
          public void onError(Throwable t) {
            // System.err.println("SendReply ob failed: " + Status.fromThrowable(t));
          }

          @Override
          public void onCompleted() {
            System.out.println("sendReply ob completed");  
          }
        };  
          

      }
        
      // add observer that could write to tail into Map<StreamObserver> obs
      private ConcurrentHashMap<Integer, StreamObserver<Op>> initTailOb(Long id) {
        // replies from tail node
        ConcurrentHashMap<Integer, StreamObserver<Op>> newMap = new ConcurrentHashMap<>();
        StreamObserver<Op> tmp;
        for(int i = 0; i < this.num_shards; i++) {
          tmp = RubbleKvStoreServiceGrpc.newStub(this.tailChan.get(id.intValue()%this.num_channels+i*this.num_channels)).doOp(
            new StreamObserver<OpReply>(){
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
            });
          newMap.put(i, tmp);
          // System.out.println("added " + i + " to tail");
        }
        return newMap;
      }

      // add an observer to comm with head nodes into Map<long, StreamObserver<Op>> head_obs
      private ConcurrentHashMap<Integer, StreamObserver<Op>> initHeadOb(Long id) {
        ConcurrentHashMap<Integer, StreamObserver<Op>> newMap = new ConcurrentHashMap<>();
        StreamObserver<Op> tmp;
        for(int i = 0; i < this.num_shards; i++) {
          // id --> determines which channel you will get
          // i --> add one client per shard to returned client map
          tmp = RubbleKvStoreServiceGrpc.newStub(this.headChan.get(id.intValue()%this.num_channels + i*this.num_channels)).doOp(
            new StreamObserver<OpReply>(){
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
            });
          newMap.put(i, tmp);
          // System.out.println("added " + i + " to head");
        }
        return newMap;
      }       
    }
        
}

       


    

    
