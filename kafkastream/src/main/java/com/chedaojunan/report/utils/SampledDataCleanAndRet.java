package com.chedaojunan.report.utils;

import java.io.IOException;
import java.util.*;

import com.chedaojunan.report.client.CoordinateConvertClient;
import com.chedaojunan.report.model.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chedaojunan.report.model.CoordinateConvertRequest;

public class SampledDataCleanAndRet {

  private static final int MININUM_SAMPLE_COUNT = 3;
  private static final double DECIMAL_DIGITS = 0.000001;
  private static final String HEAD_CODE = "001";
  private static final int coordinateConvertLength;
  private static Properties kafkaProperties = null;
  private static CoordinateConvertClient coordinateConvertClient;

  static CalculateUtils calculateUtils = new CalculateUtils();
  private static final Logger LOG = LoggerFactory.getLogger(SampledDataCleanAndRet.class);

  static {
    kafkaProperties = ReadProperties.getProperties(KafkaConstants.PROPERTIES_FILE_NAME);
    coordinateConvertLength = Integer.parseInt(kafkaProperties.getProperty(KafkaConstants.COORDINATE_CONVERT_LENGTH));
    coordinateConvertClient = CoordinateConvertClient.getInstance();
  }

  @SuppressWarnings("unchecked")
  public static Comparator<FixedFrequencyAccessData> sortingByServerTime =
          (o1, o2) -> (int) (Long.parseLong(o1.getServerTime()) -
                  Long.parseLong(o2.getServerTime()));

  // 60s数据采样返回
  public static ArrayList<FixedFrequencyAccessGpsData> sampleKafkaData(List<FixedFrequencyAccessGpsData> batchList) {

    int batchListSize = batchList.size();
    ArrayList sampleOver = new ArrayList(); // 用list存取样后数据
    CopyProperties copyProperties = new CopyProperties();
    int numRange = 50; // 数值取值范围[0,50)


    // 采集步长
    int stepLength = batchListSize / MININUM_SAMPLE_COUNT;
    // 60s内数据少于3条处理
    if (batchListSize >= MININUM_SAMPLE_COUNT) {
      FixedFrequencyAccessGpsData accessData1;
      FixedFrequencyAccessGpsData accessData2;
      FixedFrequencyAccessGpsData accessData3;
      FixedFrequencyAccessGpsData accessData4;
      for (int i = 0; i < batchListSize; i += stepLength) {
        if (i == 0) {
          accessData4 = batchList.get(i);
//          gpsMap.put(accessData4.getLongitude() + "," + accessData4.getLatitude(), accessData4.getLongitude() + "," + accessData4.getLatitude());
          sampleOver.add(accessData4);
        } else {
          accessData1 = batchList.get(i - stepLength);
          accessData2 = batchList.get(i);
            // TODO 根据经纬度判断数据是否有效
            if (accessData1.getLatitude() == accessData2.getLatitude()
                    && accessData1.getLongitude() == accessData2.getLongitude()
                    && Double.doubleToLongBits(accessData2.getLatitude())!=0.0
                    && Double.doubleToLongBits(accessData2.getLongitude())!=0.0) {
              accessData3 = copyProperties.clone(accessData2);
              double longitude = calculateUtils.add(
                      calculateUtils.randomReturn(numRange, DECIMAL_DIGITS), accessData2.getLongitude());
              double latitude = calculateUtils.add(
                      calculateUtils.randomReturn(numRange, DECIMAL_DIGITS), accessData2.getLatitude());
              accessData3.setLongitude(longitude);
              accessData3.setLatitude(latitude);
//            gpsMap.put(longitude + "," + latitude, accessData2.getLongitude() + "," + accessData2.getLatitude());
              sampleOver.add(accessData3);
            } else {
//            gpsMap.put(accessData2.getLongitude() + "," + accessData2.getLatitude(), accessData2.getLongitude() + "," + accessData2.getLatitude());
              sampleOver.add(accessData2);
            }
        }
      }
      // 车停止数据量不足3条，不做数据融合
    } else {
      for (int i = 0; i < batchListSize; i++) {
        sampleOver.add(batchList.get(i));
      }
    }

    return sampleOver;
  }

  // 返回抓路服务请求参数
  public static AutoGraspRequest autoGraspRequestRet(ArrayList<FixedFrequencyAccessGpsData> listSample) {
    if (listSample.size() > 0) {
      FixedFrequencyAccessGpsData accessData = listSample.get(0);
      if (HEAD_CODE.equals(accessData.getSourceId())) {
        return sampleDataHaveDirection(listSample);
      } else {
        return sampleDataNoDirection(listSample);
      }
    }
    return null;
  }

  // 采样数据中无direction
  public static AutoGraspRequest sampleDataNoDirection(ArrayList<FixedFrequencyAccessGpsData> listSample) {
    FixedFrequencyAccessGpsData accessData1;
    FixedFrequencyAccessGpsData accessData2;
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
          accessData1 = listSample.get(i - 1);
          accessData2 = listSample.get(i);

          // TODO 需确认数据端收集的数据格式，并转化为UTC格式
          times.add(accessData2.getServerTime() == "" ? 0L : dateUtils.getUTCTimeFromLocal(Long.valueOf(accessData2.getServerTime())));
          speeds.add(accessData2.getGpsSpeed());
          location = new Pair<>(accessData2.getCorrectedLongitude(), accessData2.getCorrectedLatitude());
          locations.add(location);
        } else {
          accessData1 = listSample.get(i);
          accessData2 = listSample.get(i + 1);

          // TODO 需确认数据端收集的数据格式，并转化为UTC格式
          times.add(accessData1.getServerTime() == "" ? 0L : dateUtils.getUTCTimeFromLocal(Long.valueOf(accessData1.getServerTime())));
          speeds.add(accessData1.getGpsSpeed());
          location = new Pair<>(accessData1.getCorrectedLongitude(), accessData1.getCorrectedLatitude());
          locations.add(location);
        }

        if (i == 0) {
          apiKey = EndpointUtils.getEndpointProperties().getProperty(EndpointConstants.GAODE_API_KEY);
          carId = accessData1.getDeviceId();
        }

        // 根据经纬度计算得出
        A = new AzimuthFromLogLatUtil(accessData1.getCorrectedLongitude(), accessData1.getCorrectedLatitude());
        B = new AzimuthFromLogLatUtil(accessData2.getCorrectedLongitude(), accessData2.getCorrectedLatitude());
        azimuthFromLogLatUtil = new AzimuthFromLogLatUtil();

        direction = azimuthFromLogLatUtil.getAzimuth(A, B);
        if (!Double.isNaN(direction)) {
          directions.add(direction);
        } else {
          directions.add(0.0);
        }
      }

      String locationString = PrepareAutoGraspRequest.convertLocationsToRequestString(locations);
      String timeString = PrepareAutoGraspRequest.convertTimeToRequstString(times);
      String speedString = PrepareAutoGraspRequest.convertSpeedToRequestString(speeds);
      String directionString = PrepareAutoGraspRequest.convertDirectionToRequestString(directions);

      AutoGraspRequest autoGraspRequest = new AutoGraspRequest(apiKey, carId, locationString, timeString, directionString, speedString);
      return autoGraspRequest;
    } else
      return null;
  }

  // 采样数据中无direction
  public static AutoGraspRequest sampleDataHaveDirection(ArrayList<FixedFrequencyAccessGpsData> listSample) {
    FixedFrequencyAccessGpsData accessData;
    List<Long> times = new ArrayList<>();
    List<Double> directions = new ArrayList<>();
    List<Double> speeds = new ArrayList<>();
    String apiKey = "";
    String carId = "";
    Pair<Double, Double> location;
    List<Pair<Double, Double>> locations = new ArrayList<>();
    DateUtils dateUtils = new DateUtils();
    int listSampleCount = listSample.size();
    if (listSampleCount > 2) {
      for (int i = 0; i < listSampleCount; i++) {
        accessData = listSample.get(i);

        // TODO 需确认数据端收集的数据格式，并转化为UTC格式
        times.add(accessData.getServerTime() == "" ? 0L : dateUtils.getUTCTimeFromLocal(Long.valueOf(accessData.getServerTime())));
        speeds.add(accessData.getGpsSpeed());
        location = new Pair<>(accessData.getCorrectedLongitude(), accessData.getCorrectedLatitude());
        locations.add(location);

        if (i == 0) {
          apiKey = EndpointUtils.getEndpointProperties().getProperty(EndpointConstants.GAODE_API_KEY);
          carId = accessData.getDeviceId();
        }
        directions.add(accessData.getDirection());
      }

      String locationString = PrepareAutoGraspRequest.convertLocationsToRequestString(locations);
      String timeString = PrepareAutoGraspRequest.convertTimeToRequstString(times);
      String speedString = PrepareAutoGraspRequest.convertSpeedToRequestString(speeds);
      String directionString = PrepareAutoGraspRequest.convertDirectionToRequestString(directions);

      AutoGraspRequest autoGraspRequest = new AutoGraspRequest(apiKey, carId, locationString, timeString, directionString, speedString);
      return autoGraspRequest;
    } else
      return null;
  }

  // 坐标转换请求参数
  public static CoordinateConvertRequest coordinateConvertRequestParm(List<FixedFrequencyAccessData> accessDataList) {
    FixedFrequencyAccessData accessData;
    Pair<Double, Double> location;
    List<Pair<Double, Double>> locations = new ArrayList<>();

    if (accessDataList != null) {
      for (int i = 0; i < accessDataList.size(); i++) {
        accessData = accessDataList.get(i);
        location = new Pair<>(accessData.getLongitude(), accessData.getLatitude());
        locations.add(location);
      }

      String locationsString = PrepareCoordinateConvertRequest.convertLocationsToRequestString(locations);
      String apiKey = EndpointUtils.getEndpointProperties().getProperty(EndpointConstants.GAODE_API_KEY);

      CoordinateConvertRequest coordinateConvertRequest = new CoordinateConvertRequest(apiKey, locationsString, null);
      return coordinateConvertRequest;
    } else
      return null;
  }

  // 数据整合
  public static ArrayList<FixedFrequencyIntegrationData> dataIntegration(List<FixedFrequencyAccessGpsData> batchList, List<FixedFrequencyAccessGpsData> sampleList, List<FixedFrequencyIntegrationData> gaodeApiResponseList) {
    ArrayList<FixedFrequencyIntegrationData> integrationDataList = new ArrayList<>();
    CopyProperties copyProperties = new CopyProperties();

    int batchListSize = batchList.size();
    int sampleListSize = sampleList.size();
    int gaodeApiResponseListSize = gaodeApiResponseList.size();

    // 整合步长
    int stepLength = batchListSize / MININUM_SAMPLE_COUNT;


    FixedFrequencyIntegrationData integrationData;
    FixedFrequencyAccessGpsData accessData;

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

  public static void addAccessDataToIntegrationData(FixedFrequencyIntegrationData integrationData, FixedFrequencyAccessGpsData accessData) {
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

    integrationData.setCorrectedLatitude(accessData.getCorrectedLatitude());
    integrationData.setCorrectedLongitude(accessData.getCorrectedLongitude());
  }

  public static void main(String[] args) throws Exception {

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

  public static FixedFrequencyIntegrationData convertToFixedFrequencyIntegrationDataPojo(String integrationDataString) {
    if (StringUtils.isEmpty(integrationDataString))
      return null;
    ObjectMapper objectMapper = ObjectMapperUtils.getObjectMapper();
    try {
      FixedFrequencyIntegrationData integrationData = objectMapper.readValue(integrationDataString, FixedFrequencyIntegrationData.class);
      return integrationData;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static AutoGraspRequest convertToAutoGraspRequest(String apiRequest) {
    if (StringUtils.isEmpty(apiRequest))
      return null;
    ObjectMapper objectMapper = ObjectMapperUtils.getObjectMapper();
    try {
      AutoGraspRequest autoGraspRequest = objectMapper.readValue(apiRequest, AutoGraspRequest.class);
      return autoGraspRequest;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  // 坐标转化调用，返回gps整合数据列表
  public static List<FixedFrequencyAccessGpsData> getCoordinateConvertResponseList(List<FixedFrequencyAccessData> accessDataList) {
    List<FixedFrequencyAccessData> accessDataListNew = null;
    List<FixedFrequencyAccessGpsData> coordinateConvertResponse;
    List<FixedFrequencyAccessGpsData> coordinateConvertResponseList = new ArrayList<>();
    CoordinateConvertRequest coordinateConvertRequest = null;
    int num = accessDataList.size() / coordinateConvertLength;
    if (accessDataList.size() > 0) {
      for (int i = 0; i <= num; i++) {
        if (accessDataListNew != null) {
          accessDataListNew.clear();
        }
        if (i < num) {
          accessDataListNew = new ArrayList<>(accessDataList.subList(i * coordinateConvertLength, (i + 1) * coordinateConvertLength));
        } else {
          if (accessDataList.size() - i * coordinateConvertLength > 0) {
            accessDataListNew = new ArrayList<>(accessDataList.subList(i * coordinateConvertLength, accessDataList.size()));
          }
        }

        if (accessDataListNew != null && accessDataListNew.size() != 0) {
          coordinateConvertRequest = SampledDataCleanAndRet.coordinateConvertRequestParm(accessDataListNew);
        }
        if (coordinateConvertRequest != null) {
          coordinateConvertResponse = coordinateConvertClient.getCoordinateConvertFromResponse(accessDataListNew, coordinateConvertRequest);
          coordinateConvertRequest = null;
          coordinateConvertResponseList.addAll(coordinateConvertResponse);
        }
      }
    }
    return coordinateConvertResponseList;
  }
}