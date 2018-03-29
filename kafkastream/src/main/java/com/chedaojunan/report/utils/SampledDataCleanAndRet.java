package com.chedaojunan.report.utils;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chedaojunan.report.client.AutoGraspApiClient;
import com.chedaojunan.report.model.AutoGraspRequestParam;
import com.chedaojunan.report.model.ExtensionParamEnum;
import com.chedaojunan.report.model.FixedFrequencyAccessData;
import com.chedaojunan.report.model.FixedFrequencyIntegrationData;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SampledDataCleanAndRet {

  private static final String TIME_PATTERN = "MM-dd-yy HH:mm:ss";
  private static final int MININUM_SAMPLE_COUNT = 3;
  private static final double DECIMAL_DIGITS = 0.000001;

  private static AutoGraspApiClient autoGraspApiClient;
  static AutoGraspRequestParam autoGraspRequestParam;
  static CalculateUtils calculateUtils = new CalculateUtils();
  private static final Logger log = LoggerFactory.getLogger(SampledDataCleanAndRet.class);

  static HashMap gpsMap = new HashMap();

  // 60s数据采样返回
  public static ArrayList<String> sampleKafkaData(List<String> batchList){

    int batchListSize = batchList.size();
    ArrayList sampleOver = new ArrayList(); // 用list存取样后数据
    CopyProperties copyProperties = new CopyProperties();
    int numRange = 50; // 数值取值范围[0,50)


    // 采集步长
    int stepLength = batchListSize / MININUM_SAMPLE_COUNT;
    // 60s内数据少于3条处理
    if (batchListSize >= MININUM_SAMPLE_COUNT) {
      FixedFrequencyAccessData accessData1;
      FixedFrequencyAccessData accessData2;
      FixedFrequencyAccessData accessData3;
      FixedFrequencyAccessData accessData4;
      for (int i = 0; i < batchListSize; i += stepLength) {
        if (i == 0) {
          accessData4 = convertToFixedAccessDataPojo(batchList.get(i));
          gpsMap.put(accessData4.getLongitude() + "," + accessData4.getLatitude(), accessData4.getLongitude() + "," + accessData4.getLatitude());
          sampleOver.add(accessData4.toString());
        } else {
          accessData1 = convertToFixedAccessDataPojo(batchList.get(i - stepLength));
          accessData2 = convertToFixedAccessDataPojo(batchList.get(i));
          // TODO 根据经纬度判断数据是否有效
          if (accessData1.getLatitude() == accessData2.getLatitude()
              && accessData1.getLongitude() == accessData2.getLongitude()) {
            accessData3 = copyProperties.clone(accessData2);
            double longitude = calculateUtils.add(
                calculateUtils.randomReturn(numRange, DECIMAL_DIGITS), accessData2.getLongitude());
            double latitude = calculateUtils.add(
                calculateUtils.randomReturn(numRange, DECIMAL_DIGITS), accessData2.getLatitude());
            accessData3.setLongitude(longitude);
            accessData3.setLatitude(latitude);
            gpsMap.put(longitude + "," + latitude, accessData2.getLongitude() + "," + accessData2.getLatitude());
            sampleOver.add(accessData3.toString());
          } else {
            gpsMap.put(accessData2.getLongitude() + "," + accessData2.getLatitude(), accessData2.getLongitude() + "," + accessData2.getLatitude());
            sampleOver.add(accessData2.toString());
          }
        }
      }
      // 车停止数据量不足3条，不做数据融合
    } else {
      for (int i = 0; i < batchListSize; i++) {
        sampleOver.add(batchList.get(i).toString());
      }
    }

    return sampleOver;
  }


  // 返回抓路服务请求参数
  public static AutoGraspRequestParam autoGraspRequestParamRet(ArrayList<String> listSample) {
    FixedFrequencyAccessData accessData1;
    FixedFrequencyAccessData accessData2;
    List<Long> times = new ArrayList<>();
    List<Double> directions = new ArrayList<>();
    Double direction;
    AzimuthFromLogLatUtil azimuthFromLogLatUtil;
    AzimuthFromLogLatUtil A;
    AzimuthFromLogLatUtil B;
    List<Double> speeds = new ArrayList<>();
    String apiKey = "";
    String carId = "";
    Pair<Double, Double> location;
    List<Pair<Double, Double>> locations = new ArrayList<>();
    DateUtils dateUtils = new DateUtils();
    int listSampleCount = listSample.size();
    if (listSampleCount > 2) {
      for (int i = 0; i < listSampleCount; i++) {
        if (i == listSampleCount - 1) {
          accessData1 = convertToFixedAccessDataPojo(listSample.get(i - 1));
          accessData2 = convertToFixedAccessDataPojo(listSample.get(i));

          // TODO 需确认数据端收集的数据格式，并转化为UTC格式
          times.add(accessData2.getServerTime() == "" ? 0L : dateUtils.getUTCTimeFromLocal(Long.valueOf(accessData2.getServerTime())));
          speeds.add(accessData2.getGpsSpeed());
          location = new Pair<>(accessData2.getLongitude(), accessData2.getLatitude());
          locations.add(location);
        } else {
          accessData1 = convertToFixedAccessDataPojo(listSample.get(i));
          accessData2 = convertToFixedAccessDataPojo(listSample.get(i + 1));

          // TODO 需确认数据端收集的数据格式，并转化为UTC格式
          times.add(accessData1.getServerTime() == "" ? 0L : dateUtils.getUTCTimeFromLocal(Long.valueOf(accessData1.getServerTime())));
          speeds.add(accessData1.getGpsSpeed());
          location = new Pair<>(accessData1.getLongitude(), accessData1.getLatitude());
          locations.add(location);
        }

        if (i == 0) {
          apiKey = EndpointUtils.getEndpointProperties().getProperty(EndpointConstants.GAODE_API_KEY);
          carId = accessData1.getDeviceId();
        }

        // 根据经纬度计算得出
        A = new AzimuthFromLogLatUtil(accessData1.getLongitude(), accessData1.getLatitude());
        B = new AzimuthFromLogLatUtil(accessData2.getLongitude(), accessData2.getLatitude());
        azimuthFromLogLatUtil = new AzimuthFromLogLatUtil();

        direction = azimuthFromLogLatUtil.getAzimuth(A, B);
        if (!Double.isNaN(direction)) {
          directions.add(direction);
        } else {
          directions.add(0.0);
        }
      }

      autoGraspApiClient = AutoGraspApiClient.getInstance();
      autoGraspRequestParam = new AutoGraspRequestParam(apiKey, carId, locations, times, directions, speeds, ExtensionParamEnum.BASE);
      return autoGraspRequestParam;
    }
    else
      return null;
  }

  // 数据整合
  public List dataIntegration(List<FixedFrequencyAccessData> batchList, List<FixedFrequencyAccessData> sampleList, List<FixedFrequencyIntegrationData> gaodeApiResponseList) throws IOException {
    List<FixedFrequencyIntegrationData> integrationDataList = new ArrayList<>();
    CopyProperties copyProperties = new CopyProperties();

    int batchListSize = batchList.size();
    int sampleListSize = sampleList.size();
    int gaodeApiResponseListSize = gaodeApiResponseList.size();

    // 整合步长
    int stepLength = batchListSize / MININUM_SAMPLE_COUNT;


    FixedFrequencyIntegrationData integrationData;
    FixedFrequencyAccessData accessData;

    // 采样数据和高德融合数据大于等于3条，并且两种数据条数相同时
    if (sampleListSize >= MININUM_SAMPLE_COUNT && gaodeApiResponseListSize >= MININUM_SAMPLE_COUNT
        && sampleListSize == gaodeApiResponseListSize) {
      for (int i = 0; i < gaodeApiResponseListSize; i++) {
        // TODO 获取高德数据整合后实体类
        integrationData = gaodeApiResponseList.get(i);
        /*gaoDeFusionReturn = (GaoDeFusionReturn)mapGaoDe.get(listSample.get(i * stepLength).getLongitude() + ","
            + listSample.get(i * stepLength).getLatitude());*/
        for (int j = i * stepLength; j < Math.min((i + 1) * stepLength, batchListSize); j++) {
            // TODO 整合高德数据
            accessData = batchList.get(j);
            addAccessDataToIntegrationData(integrationData, accessData);
            integrationDataList.add(copyProperties.clone(integrationData));
        }
      }
    } else {
      // TODO 高德地图不整合，返回(结构化数据和高德字段设置空)
      for (int i = 0; i < batchListSize; i++) {
        accessData = batchList.get(i);
        integrationData = new FixedFrequencyIntegrationData(accessData);
        integrationDataList.add(integrationData);
      }
    }
    return integrationDataList;
  }

  public void addAccessDataToIntegrationData(FixedFrequencyIntegrationData integrationData, FixedFrequencyAccessData accessData) {
    integrationData.setDeviceId(accessData.getDeviceId());
    integrationData.setDeviceImei(accessData.getDeviceImei());
    integrationData.setLocalTime(accessData.getLocalTime());
    integrationData.setServerTime(accessData.getServerTime());
    integrationData.setTripId(accessData.getTripId());
    integrationData.setLatitude(accessData.getLatitude());
    integrationData.setLongitude(accessData.getLongitude());
    integrationData.setAltitude(accessData.getAltitude());
    integrationData.setGpsSpeed(accessData.getGpsSpeed());
    integrationData.setDirection(accessData.getDirection());
    integrationData.setYawRate(accessData.getYawRate());
    integrationData.setAccelerateZ(accessData.getAccelerateZ());
    integrationData.setRollRate(accessData.getRollRate());
    integrationData.setAccelerateX(accessData.getAccelerateX());
    integrationData.setPitchRate(accessData.getPitchRate());
    integrationData.setAccelerateY(accessData.getAccelerateY());
    integrationData.setSourceId(accessData.getSourceId());
  }

  public static void main(String[] args) throws Exception{

    /*List<FixedFrequencyAccessData> batchList = new ArrayList();

    SampledDataCleanAndRet sampledData = new SampledDataCleanAndRet();
    autoGraspApiClient = AutoGraspApiClient.getInstance();

    // 1.60s数据采样返回
    List<FixedFrequencyAccessData> listSample = sampledData.sampleKafkaData(batchList);

    if (listSample.size() >= 3) {
      // 2.高德抓路服务参数返回
      AutoGraspRequestParam autoGraspRequestParam = sampledData.autoGraspRequestParamRet(listSample);
      // 3.调用抓路服务
      AutoGraspResponse response = autoGraspApiClient.getAutoGraspResponse(autoGraspRequestParam);
      // 4. TODO 调用交通态势服务参数和服务

    }
    // TODO 以下为高德整合返回数据接受对象
    Map gaoDeMap = new HashMap();

    // 5.数据整合
    List integrationDataList = sampledData.dataIntegration(batchList, listSample, gaoDeMap);

    // 6.入库datahub
    WriteDatahubUtil writeDatahubUtil = new WriteDatahubUtil();
    if (integrationDataList.size() > 0) {
      int failNum = writeDatahubUtil.putRecords(integrationDataList);
      if (failNum > 0) {
        log.info("整合数据入库datahub失败!");
      }
    }*/
  }

  public static FixedFrequencyAccessData convertToFixedAccessDataPojo(String accessDataString) {
    if (StringUtils.isEmpty(accessDataString))
      return null;
    ObjectMapper objectMapper = ObjectMapperUtils.getObjectMapper();
    try {
      FixedFrequencyAccessData accessData = objectMapper.readValue(accessDataString, FixedFrequencyAccessData.class);
      return accessData;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static AutoGraspRequestParam convertToAutoGraspRequestParam (String apiRequest) {
    if (StringUtils.isEmpty(apiRequest))
      return null;
    ObjectMapper objectMapper = ObjectMapperUtils.getObjectMapper();
    try {
      AutoGraspRequestParam autoGraspRequestParam = objectMapper.readValue(apiRequest, AutoGraspRequestParam.class);
      return autoGraspRequestParam;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static long convertTimeStringToEpochSecond(String timeString) {
    //System.out.println("haha:" + timeString);
    ZonedDateTime dateTime = ZonedDateTime.parse(timeString, DateTimeFormatter
        .ofPattern(TIME_PATTERN).withZone(ZoneId.of("UTC")));
    return dateTime.toEpochSecond();
  }

}