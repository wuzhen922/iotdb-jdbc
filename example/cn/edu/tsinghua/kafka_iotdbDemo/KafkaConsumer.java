package cn.edu.tsinghua.kafka_iotdbDemo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.edu.tsinghua.kafka_iotdbDemo.dataModel.IoTModel;
import cn.edu.tsinghua.kafka_iotdbDemo.dataModel.KMXModel;
import cn.edu.tsinghua.kafka_iotdbDemo.dataModel.ModelTransformer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;
import kafka.serializer.StringDecoder;
import kafka.utils.VerifiableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaConsumer {

	private final ConsumerConnector consumer;
	private final static int threadsNum = 5; // 代表消费线程数
	private static Logger logger = LoggerFactory.getLogger(KafkaConsumer.class);
	public final static String TOPIC = "defaultUser-defaultSpace-raw";
	private ExecutorService executor;

	private KafkaConsumer() {
		// Consumer配置
		Properties props = new Properties();

		// zookeeper 配置
		props.put("zookeeper.connect", "127.0.0.1:2181");

		// group 代表一个消费组
		props.put("group.id", "consumeGroup");

		// zk连接超时等待时间
		props.put("zookeeper.session.timeout.ms", "400");

		// a ZooKeeper ‘follower’ can be behind the master before an error
		// occurs
		props.put("zookeeper.sync.time.ms", "200");
		props.put("rebalance.max.retries", "5");

		// Backoff time between retries during rebalance
		props.put("rebalance.backoff.ms", "1200");

		// how often updates to the consumed offsets are written to ZooKeeper
		props.put("auto.commit.interval.ms", "1000");

		// What to do when there is no initial offset in ZooKeeper or if an
		// offset is out of range:
		// * smallest : automatically reset the offset to the smallest offset
		props.put("auto.offset.reset", "smallest");

		// 序列化类
		props.put("serializer.class", "kafka.serializer.StringEncoder");

		// consumer实例化
		ConsumerConfig config = new ConsumerConfig(props);
		consumer = kafka.consumer.Consumer.createJavaConsumerConnector(config);
	}

	void consume() throws Exception {
		// 指定消费者线程数
		Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
		topicCountMap.put(TOPIC, new Integer(threadsNum));

		// 指定数据的解码器
		StringDecoder keyDecoder = new StringDecoder(new VerifiableProperties());
		StringDecoder valueDecoder = new StringDecoder(new VerifiableProperties());

		Map<String, List<KafkaStream<String, String>>> consumerMap = consumer
				.createMessageStreams(topicCountMap, keyDecoder, valueDecoder); // 三个String分别为TOPIC、Key、Value

		// 获取data
		List<KafkaStream<String, String>> streams = consumerMap.get(TOPIC);

		// 多线程消费
		executor = Executors.newFixedThreadPool(threadsNum); // 创建线程池，共有threadsNum个线程
		for (final KafkaStream<String, String> stream : streams) {
			executor.submit(new KMXConsumerThread(stream)); // 运行线程
		}
	}

	public static void main(String[] args) throws Exception {
		new KafkaConsumer().consume();
	}
}

class KMXConsumerThread implements Runnable {

	private KafkaStream<String, String> stream;
	private SendDataToIotdb sendDataToIotdb;
	private static Logger logger = LoggerFactory.getLogger(KMXConsumerThread.class);

	public KMXConsumerThread(KafkaStream<String, String> stream) throws Exception {
		this.stream = stream;
		this.sendDataToIotdb = new SendDataToIotdb();
		this.sendDataToIotdb.connectToIotdb();
	}

	public void run() {
		ConsumerIterator<String, String> it = stream.iterator();
		while (it.hasNext()) {
			MessageAndMetadata<String, String> consumerIterator = it.next();
			String uploadMessage = consumerIterator.message();
			try {
				KMXModel kmxModel = new KMXModel(uploadMessage);
				List<IoTModel> ioTModels = ModelTransformer.KMXToIoT(kmxModel);
				for(IoTModel ioTModel : ioTModels) {
					sendDataToIotdb.sendData(ioTModel);
				}
			} catch (Exception ex) {
				System.out.println(uploadMessage);
				System.out.println("SQLException: " + ex.getMessage());
			}
		}
	}
}