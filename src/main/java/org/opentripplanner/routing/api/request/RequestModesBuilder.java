package org.opentripplanner.routing.api.request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.opentripplanner.transit.model.basic.MainAndSubMode;
import org.opentripplanner.transit.model.basic.SubMode;
import org.opentripplanner.transit.model.basic.TransitMode;

public class RequestModesBuilder {

  private StreetMode accessMode;
  private StreetMode egressMode;
  private StreetMode directMode;
  private StreetMode transferMode;
  private final HashSet<TransitMode> transitModes;

  RequestModesBuilder(RequestModes origin) {
    this.accessMode = origin.accessMode;
    this.egressMode = origin.egressMode;
    this.directMode = origin.directMode;
    this.transferMode = origin.transferMode;
    this.transitModes = (HashSet<TransitMode>) origin.transitModes;
  }

  public StreetMode accessMode() {
    return accessMode;
  }

  public RequestModesBuilder withAccessMode(StreetMode accessMode) {
    this.accessMode = accessMode;
    return this;
  }

  public StreetMode egressMode() {
    return egressMode;
  }

  public RequestModesBuilder withEgressMode(StreetMode egressMode) {
    this.egressMode = egressMode;
    return this;
  }

  public StreetMode directMode() {
    return directMode;
  }

  public RequestModesBuilder withDirectMode(StreetMode directMode) {
    this.directMode = directMode;
    return this;
  }

  public StreetMode transferMode() {
    return transferMode;
  }

  public RequestModesBuilder withTransferMode(StreetMode transferMode) {
    this.transferMode = transferMode;
    return this;
  }

  public RequestModesBuilder withAllStreetModes(StreetMode streetMode) {
    return withAccessMode(streetMode)
      .withEgressMode(streetMode)
      .withDirectMode(streetMode)
      .withTransferMode(streetMode);
  }

  /**
   * Returns a set of AllowedModes that will cover all available TransitModes standard (without carpooling).
   */
  // Car-pooling
  public static Set<TransitMode> getAllTransitModesExceptCarpooling() {
    return Arrays
      .stream(TransitMode.values())
      .filter(mode ->
        mode != TransitMode.CARPOOL &&
        mode != TransitMode.CarPoolingRequest &&
        mode != TransitMode.CarPoolingOffer
      )
      .collect(Collectors.toCollection(() -> EnumSet.noneOf(TransitMode.class)));
  }

  public RequestModes build() {
    return new RequestModes(this);
  }

  public Set<TransitMode> transitModes() {
    return transitModes;
  }
}
