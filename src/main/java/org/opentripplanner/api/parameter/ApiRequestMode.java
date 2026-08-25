package org.opentripplanner.api.parameter;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.opentripplanner.transit.model.basic.TransitMode;

public enum ApiRequestMode {
  WALK(),
  BICYCLE(),
  SCOOTER(),
  CAR(),
  TRAM(TransitMode.TRAM),
  SUBWAY(TransitMode.SUBWAY),
  RAIL(TransitMode.RAIL),
  BUS(TransitMode.BUS),
  FERRY(TransitMode.FERRY),
  CABLE_CAR(TransitMode.CABLE_CAR),
  GONDOLA(TransitMode.GONDOLA),
  FUNICULAR(TransitMode.FUNICULAR),
  TRANSIT(TransitMode.modesConsideredTransitByUsers()),
  AIRPLANE(TransitMode.AIRPLANE),
  TROLLEYBUS(TransitMode.TROLLEYBUS),
  MONORAIL(TransitMode.MONORAIL),
  CARPOOL(TransitMode.CARPOOL),
  TAXI(TransitMode.TAXI),
  FLEX(),
  // Car-pooling
  CarPoolingRequest(TransitMode.CarPoolingRequest),
  CarPoolingOffer(TransitMode.CarPoolingOffer),
  TaxiRequest(TransitMode.TaxiRequest);

  private final List<TransitMode> transitModes;

  ApiRequestMode(TransitMode... transitModes) {
    this.transitModes = List.of(transitModes);
  }

  ApiRequestMode(TransitMode transitMode) {
    this.transitModes = List.of(transitMode);
  }

  ApiRequestMode() {
    this.transitModes = List.of();
  }

  public Collection<TransitMode> getTransitModes() {
    return transitModes;
  }
}
