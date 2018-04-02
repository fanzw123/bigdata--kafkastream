package com.chedaojunan.report;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chedaojunan.report.client.AutoGraspApiClient;
import com.chedaojunan.report.common.Constants;
import com.chedaojunan.report.model.AutoGraspRequest;
import com.chedaojunan.report.model.FixedFrequencyAccessData;
import com.chedaojunan.report.model.FixedFrequencyIntegrationData;
import com.chedaojunan.report.serdes.ArrayListSerde;
import com.chedaojunan.report.serdes.SerdeFactory;
import com.chedaojunan.report.service.ExternalApiExecutorService;
import com.chedaojunan.report.utils.EndpointConstants;
import com.chedaojunan.report.utils.EndpointUtils;
import com.chedaojunan.report.utils.FixedFrequencyAccessDataTimestampExtractor;
import com.chedaojunan.report.utils.KafkaConstants;
import com.chedaojunan.report.utils.ReadProperties;
import com.chedaojunan.report.utils.SampledDataCleanAndRet;
import com.chedaojunan.report.utils.WriteDatahubUtil;

import static java.lang.System.exit;

public class DataEnrich {

  private static final Logger LOG = LoggerFactory.getLogger(DataEnrich.class);
  private static final long TIMEOUT_PER_GAODE_API_REQUEST_IN_NANO_SECONDS = 10000000000L;
  private static final String ALL_KEY = "ALL";

  private static Properties kafkaProperties = null;

  private static final int kafkaWindowLengthInSeconds;

  private static final Serde<String> stringSerde;

  private static final Serde<FixedFrequencyAccessData> fixedFrequencyAccessDataSerde;

  private static Comparator<FixedFrequencyAccessData> sortingByServerTime;

  private static AutoGraspApiClient autoGraspApiClient;

  static {
    try (InputStream inputStream = EndpointUtils.class.getClassLoader().getResourceAsStream(KafkaConstants.PROPERTIES_FILE_NAME)) {
      kafkaProperties = new Properties();
      kafkaProperties.load(inputStream);
      //inputStream.close();
    } catch (IOException e) {
      LOG.error("Error occurred while reading properties file. ", e);
      exit(1);
    }
    stringSerde = Serdes.String();
    Map<String, Object> serdeProp = new HashMap<>();
    fixedFrequencyAccessDataSerde = SerdeFactory.createSerde(FixedFrequencyAccessData.class, serdeProp);
    kafkaWindowLengthInSeconds = Integer.parseInt(kafkaProperties.getProperty(KafkaConstants.KAFKA_WINDOW_DURATION));
    autoGraspApiClient = AutoGraspApiClient.getInstance();
    sortingByServerTime =
        (o1, o2) -> (int) (Long.parseLong(o1.getServerTime()) -
            Long.parseLong(o2.getServerTime()));
  }

  public static void main(String[] args) {
    String rawDataTopic = kafkaProperties.getProperty(KafkaConstants.KAFKA_RAW_DATA_TOPIC);

    final KafkaStreams sampledRawDataStream = buildDataStream(rawDataTopic);

    sampledRawDataStream.start();

    // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
    Runtime.getRuntime().addShutdownHook(new Thread(sampledRawDataStream::close));
  }

  private static Properties getStreamConfig() {
    final Properties streamsConfiguration = new Properties();
    String kafkaApplicationName = kafkaProperties.getProperty(KafkaConstants.KAFKA_STREAM_APPLICATION_NAME);
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG,
        String.join(KafkaConstants.HYPHEN, kafkaApplicationName, UUID.randomUUID().toString()));
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
        kafkaProperties.getProperty(KafkaConstants.KAFKA_BOOTSTRAP_SERVERS));
    // Specify default (de)serializers for record keys and for record values.
    streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
        Serdes.String().getClass().getName());
    streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
        Serdes.String().getClass().getName());
    streamsConfiguration.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, FixedFrequencyAccessDataTimestampExtractor.class);
    streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaProperties.getProperty(KafkaConstants.AUTO_OFFSET_RESET_CONFIG));

    // TODO: whether state store is needed
    /*String uuid = UUID.randomUUID().toString();
    String stateDir = "/tmp/" + uuid;
    streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);*/

    return streamsConfiguration;
  }

  static KafkaStreams buildDataStream(String inputTopic) {
    final Properties streamsConfiguration = getStreamConfig();

    KStreamBuilder builder = new KStreamBuilder();
    KStream<String, String> kStream = builder.stream(inputTopic);
    WriteDatahubUtil writeDatahubUtil = new WriteDatahubUtil();

    final KStream<String, FixedFrequencyIntegrationData> enrichedDataStream = kStream
        .map(
            (key, rawDataString) ->
                new KeyValue<>(SampledDataCleanAndRet.convertToFixedAccessDataPojo(rawDataString).getDeviceId(), SampledDataCleanAndRet.convertToFixedAccessDataPojo(rawDataString))
        )
        .groupByKey(stringSerde, fixedFrequencyAccessDataSerde)
        .aggregate(
            // the initializer
            () -> {
              return new ArrayList<>();
            },
            // the "add" aggregator
                (windowedCarId, record, queue) -> {
                  if (!queue.contains(record))
                    queue.add(record);
                  return queue;
                },
            TimeWindows.of(TimeUnit.SECONDS.toMillis(kafkaWindowLengthInSeconds)),
            new ArrayListSerde<>(fixedFrequencyAccessDataSerde)
        )
        .toStream()
        .map((windowedString, accessDataList) -> {
          long windowStartTime = windowedString.window().start();
          long windowEndTime = windowedString.window().end();
          accessDataList.sort(sortingByServerTime);
          ArrayList<FixedFrequencyAccessData> sampledDataList = SampledDataCleanAndRet.sampleKafkaData(accessDataList);
          AutoGraspRequest autoGraspRequest = SampledDataCleanAndRet.autoGraspRequestRet(sampledDataList);
          System.out.println("apiQuest: " + autoGraspRequest);
          String dataKey = String.join("-", String.valueOf(windowStartTime), String.valueOf(windowEndTime));
          List<FixedFrequencyIntegrationData> gaodeApiResponseList = new ArrayList<>();
          if (autoGraspRequest != null)
            gaodeApiResponseList = autoGraspApiClient.getTrafficInfoFromAutoGraspResponse(autoGraspRequest);
          ArrayList<FixedFrequencyAccessData> rawDataList = accessDataList
              .stream()
              .collect(Collectors.toCollection(ArrayList::new));
          ArrayList<FixedFrequencyIntegrationData> enrichedData = SampledDataCleanAndRet.dataIntegration(rawDataList, sampledDataList, gaodeApiResponseList);
          // 整合数据入库datahub
          if(CollectionUtils.isNotEmpty(enrichedData)) {
            writeDatahubUtil.putRecords(enrichedData);
          }
          return new KeyValue<>(dataKey, enrichedData);
        })
        .flatMapValues(gaodeApiResponseList ->
            gaodeApiResponseList
                .stream()
                .collect(Collectors.toList()));

    enrichedDataStream.print();

    return new KafkaStreams(builder, streamsConfiguration);

  }

  static KafkaStreams buildDataStreamNew (String inputTopic) {
    final Properties streamsConfiguration = getStreamConfig();

    KStreamBuilder builder = new KStreamBuilder();
    KStream<String, String> kStream = builder.stream(inputTopic);
    WriteDatahubUtil writeDatahubUtil = new WriteDatahubUtil();

    final KStream<Windowed<String>, FixedFrequencyIntegrationData> enrichedDataStream = kStream
        //final KStream<Windowed<String>, String> enrichedDataStream = kStream
        .map(
            (key, rawDataString) ->
                new KeyValue<>(ALL_KEY, rawDataString)
        )
        .groupByKey()
        .aggregate(
            // the initializer
            () -> {
              return new ArrayList<>();
            },
            // the "add" aggregator
            (windowedCarId, record, queue) -> {
              if (!queue.contains(record))
                queue.add(record);
              return queue;
            },
            TimeWindows.of(TimeUnit.SECONDS.toMillis(kafkaWindowLengthInSeconds)),
            new ArrayListSerde<>(stringSerde)
        )
        .toStream()
        .mapValues(accessDataStringList ->
            accessDataStringList
                .stream()
                .map(rawAccessDataString ->
                    SampledDataCleanAndRet.convertToFixedAccessDataPojo(rawAccessDataString))
                .collect(Collectors.toCollection(ArrayList::new))
        )
        //KStream<Windowed<String>, ArrayList<FixedFrequencyAccessData>> -> KStream<Windowed<String>, ArrayList<ArrayList<FixedFrequencyAccessData>>>
        .mapValues(accessDataList -> {
          ArrayList<ArrayList<FixedFrequencyAccessData>> accessDataByDeviceIdList = accessDataList
              .stream()
              .collect(Collectors.groupingBy(FixedFrequencyAccessData::getDeviceId))
              .values()
              .stream()
              .map(list -> list.stream()
                  .reduce(new ArrayList<FixedFrequencyAccessData>(),
                      (list1, data) -> {
                        list1.add(data);
                        return list1;
                      },
                      (list1, list2) -> {
                        list1.addAll(list2);
                        return list1;
                      })
              ).collect(Collectors.toCollection(ArrayList::new));
          return accessDataByDeviceIdList;
        })
        //KStream<Windowed<String>, ArrayList<ArrayList<FixedFrequencyAccessData>>>
        .flatMapValues(accessDataByDeviceIdList -> {
          ArrayList<FixedFrequencyIntegrationData> enrichedDatList = new ArrayList<>();
          List<Future<?>> futures = accessDataByDeviceIdList
              .stream()
              .map(
                  accessDataList -> ExternalApiExecutorService.getExecutorService().submit(() -> {
                    accessDataList.sort(sortingByServerTime);
                    ArrayList<FixedFrequencyAccessData> sampledDataList = SampledDataCleanAndRet.sampleKafkaData(new ArrayList<>(accessDataList));
                    AutoGraspRequest autoGraspRequest = SampledDataCleanAndRet.autoGraspRequestRet(sampledDataList);
                    System.out.println("apiQuest: " + autoGraspRequest);
                    List<FixedFrequencyIntegrationData> gaodeApiResponseList = new ArrayList<>();
                    if (autoGraspRequest != null)
                      gaodeApiResponseList = autoGraspApiClient.getTrafficInfoFromAutoGraspResponse(autoGraspRequest);
                    ArrayList<FixedFrequencyIntegrationData> enrichedData = SampledDataCleanAndRet.dataIntegration(accessDataList, sampledDataList, gaodeApiResponseList);
                    enrichedData
                        .stream()
                        .forEach(data -> enrichedDatList.add(data));
                  })
              ).collect(Collectors.toList());
          ExternalApiExecutorService.getFuturesWithTimeout(futures, TIMEOUT_PER_GAODE_API_REQUEST_IN_NANO_SECONDS, "calling Gaode API");
          // 整合数据入库datahub
          if (CollectionUtils.isNotEmpty(enrichedDatList)) {
            writeDatahubUtil.putRecords(enrichedDatList);
          }
          return enrichedDatList;
        });

    enrichedDataStream.print();

    return new KafkaStreams(builder, streamsConfiguration);

  }
}
