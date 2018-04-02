package com.chedaojunan.report.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chedaojunan.report.model.AutoGraspResponse;
import com.chedaojunan.report.model.Evaluation;
import com.chedaojunan.report.model.FixedFrequencyIntegrationData;
import com.chedaojunan.report.model.GaoDeApiResponse;
import com.chedaojunan.report.model.RectangleTrafficInfoResponse;
import com.chedaojunan.report.model.RoadInfo;
import com.chedaojunan.report.model.TrafficInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class ResponseUtils {

  private static final Logger LOG = LoggerFactory.getLogger(ResponseUtils.class);

  private static final String INVALID_CROSSPOINT = "0,0";

  public static String validateStringOrStringArray (JsonNode jsonNode) {
    String nodeValue = "";
    switch (jsonNode.getNodeType()) {
      case STRING:
        nodeValue = jsonNode.asText();
        break;
      case ARRAY:
        break;
      default:
        LOG.error("this json node value can only be String or String[], but found %s", jsonNode.getNodeType());
    }
    return nodeValue;
  }

  public static String getValidGPS(int index, List<String> autoGraspRequestGpsList, List<RoadInfo> autoGraspResponseRoadInfoList) {
    RoadInfo roadInfo = autoGraspResponseRoadInfoList.get(index);
    String origGPS = autoGraspRequestGpsList.get(index);
    String crosspoint = roadInfo.getCrosspoint();
    if (crosspoint.equals(INVALID_CROSSPOINT))
      return origGPS;
    else
      return crosspoint;
  }

  public static AutoGraspResponse convertStringToAutoGraspResponse(String autoGraspResponseString) {
    AutoGraspResponse autoGraspResponse = new AutoGraspResponse();
    try {
      JsonNode autoGraspResponseNode = ObjectMapperUtils.getObjectMapper().readTree(autoGraspResponseString);
      if (autoGraspResponseNode == null)
        return null;
      else {
        int autoGraspStatus = autoGraspResponseNode.get(AutoGraspResponse.STATUS).asInt();
        String autoGraspInfoString = autoGraspResponseNode.get(AutoGraspResponse.INFO).asText();
        String autoGraspInfoCode = autoGraspResponseNode.get(AutoGraspResponse.INFO_CODE).asText();
        int autoGraspCount = autoGraspResponseNode.get(AutoGraspResponse.COUNT).asInt();
        ArrayNode roadInfoArrayNode = (ArrayNode) autoGraspResponseNode.get(AutoGraspResponse.ROADS);
        List<RoadInfo> roadInfoList = new ArrayList<>();
        roadInfoArrayNode
            .forEach(roadInfoNode -> roadInfoList.add(convertJsonNodeToRoadInfo(roadInfoNode)));

        autoGraspResponse.setCount(autoGraspCount);
        autoGraspResponse.setRoadInfoList(roadInfoList);
        autoGraspResponse.setInfo(autoGraspInfoString);
        autoGraspResponse.setInfoCode(autoGraspInfoCode);
        autoGraspResponse.setStatus(autoGraspStatus);
        return autoGraspResponse;
      }
    } catch (IOException e) {
      LOG.debug("cannot get roadInfo string %s", e.getMessage());
      return null;
    }
  }

  public static RoadInfo convertJsonNodeToRoadInfo(JsonNode roadInfoNode) {
    if (roadInfoNode == null)
      return null;
    else {

      String crosspoint = roadInfoNode.get(RoadInfo.CROSS_POINT).asText();
      JsonNode roadNameNode = roadInfoNode.get(RoadInfo.ROAD_NAME);
      String roadName = ResponseUtils.validateStringOrStringArray(roadNameNode);
      int roadLevel = roadInfoNode.get(RoadInfo.ROAD_LEVEL).asInt();
      int maxSpeed = roadInfoNode.get(RoadInfo.MAX_SPEED).asInt();
      JsonNode intersectionNode = roadInfoNode.get(RoadInfo.INTERSECTION);
      String intersection = ResponseUtils.validateStringOrStringArray(intersectionNode);
      String intersectionDistance = roadInfoNode.get(RoadInfo.INTERSECTION_DISTANCE).asText();
      RoadInfo roadInfo = new RoadInfo();
      roadInfo.setCrosspoint(crosspoint);
      roadInfo.setRoadname(roadName);
      roadInfo.setRoadlevel(roadLevel);
      roadInfo.setMaxspeed(maxSpeed);
      roadInfo.setIntersection(intersection);
      roadInfo.setIntersectiondistance(intersectionDistance);
      return roadInfo;
    }

  }

  public static void enrichDataWithAutoGraspResponse(FixedFrequencyIntegrationData integrationData,
                                              int index, List<String> autoGraspRequestGpsList, List<RoadInfo> roadInfoList,
                                              AutoGraspResponse autoGraspResponse, String requestTimestamp, String requestId) {
    RoadInfo roadInfo = roadInfoList.get(index);
    String validGPS = getValidGPS(index, autoGraspRequestGpsList, roadInfoList);
    integrationData.setRoadApiStatus(autoGraspResponse.getStatus());
    integrationData.setCrosspoint(validGPS);
    integrationData.setRoadName(roadInfo.getRoadname().toString());
    integrationData.setMaxSpeed(roadInfo.getMaxspeed());
    integrationData.setRoadLevel(roadInfo.getRoadlevel());
    integrationData.setIntersection(roadInfo.getIntersection().toString());
    integrationData.setIntersectionDistance(roadInfo.getIntersectiondistance());
    integrationData.setTrafficRequestId(requestId);
    integrationData.setTrafficRequestTimesamp(requestTimestamp);
  }

  public static FixedFrequencyIntegrationData enrichDataWithTrafficInfoResponse(FixedFrequencyIntegrationData integrationData,
                                                                         int trafficInfoResponseStatus, String congestionInfo) {
    integrationData.setTrafficApiStatus(trafficInfoResponseStatus);
    integrationData.setCongestionInfo(congestionInfo);

    return integrationData;
  }

  public static RectangleTrafficInfoResponse convertToTrafficInfoResponse (String trafficInfoResponseString) {
    RectangleTrafficInfoResponse trafficInfoResponse = new RectangleTrafficInfoResponse();
    try {
      JsonNode trafficInfoResponseNode = ObjectMapperUtils.getObjectMapper().readTree(trafficInfoResponseString);
      if (trafficInfoResponseNode == null)
        return null;
      else {
        int trafficInfoStatus = trafficInfoResponseNode.get(GaoDeApiResponse.STATUS).asInt();
        String trafficInfoString = trafficInfoResponseNode.get(GaoDeApiResponse.INFO).asText();
        String trafficInfoCode = trafficInfoResponseNode.get(GaoDeApiResponse.INFO_CODE).asText();

        JsonNode trafficInfoNode = trafficInfoResponseNode.get(RectangleTrafficInfoResponse.TRAFFIC_INFO);
        TrafficInfo trafficInfo = new TrafficInfo();
        String trafficDescription = trafficInfoNode.get(TrafficInfo.DESCRIPTION).asText();

        JsonNode evaluationNode = trafficInfoNode.get(TrafficInfo.EVALUATION);
        Evaluation evaluation = new Evaluation();
        String evaluationExpedite = evaluationNode.get(Evaluation.EXPEDITE).asText();
        String evaluationCongested = evaluationNode.get(Evaluation.CONGESTED).asText();
        String evaluationBlocked = evaluationNode.get(Evaluation.BLOCKED).asText();
        String evaluationUnknown = evaluationNode.get(Evaluation.UNKNOWN).asText();
        String evaluationDescription = evaluationNode.get(Evaluation.DESCRIPTION).asText();
        String evaluationStatus = evaluationNode.get(Evaluation.STATUS).asText();
        evaluation.setBlocked(evaluationBlocked);
        evaluation.setCongested(evaluationCongested);
        evaluation.setDescription(evaluationDescription);
        evaluation.setExpedite(evaluationExpedite);
        evaluation.setStatus(evaluationStatus);
        evaluation.setUnknown(evaluationUnknown);

        trafficInfo.setDescription(trafficDescription);
        trafficInfo.setEvaluation(evaluation);

        trafficInfoResponse.setTrafficInfo(trafficInfo);
        trafficInfoResponse.setInfo(trafficInfoString);
        trafficInfoResponse.setInfoCode(trafficInfoCode);
        trafficInfoResponse.setStatus(trafficInfoStatus);

        return trafficInfoResponse;
      }
    } catch (IOException e){
      LOG.debug("cannot get roadInfo string %s", e.getMessage());
      return null;
    }

  }
}
