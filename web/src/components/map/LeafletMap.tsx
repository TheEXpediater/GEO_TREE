import "leaflet/dist/leaflet.css";

import L from "leaflet";
import { useEffect, useRef } from "react";

import { cn } from "@/lib/cn";

/**
 * Interactive map for the web administration (Leaflet + OpenStreetMap raster tiles, attribution
 * always visible). Markers come only from API records with valid coordinates; nothing is invented.
 * Styled after the Android field map: forest-green tree dots with a white ring, a larger clay ring
 * for the selected tree.
 */

// PSAU / Magalang deployment area (same box as the Android OfflineMapRegion): the view when
// nothing can be placed yet.
export const DEFAULT_CENTER: [number, number] = [15.219, 120.6815];
export const DEFAULT_ZOOM = 14;
const SINGLE_MARKER_ZOOM = 17;
const FIT_MAX_ZOOM = 18;

export const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
export const TILE_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors';

export type MarkerVariant = "tree" | "archived" | "evidence" | "evidence-uncertain" | "evidence-failed";

export interface MapMarker {
  id: string;
  lat: number;
  lng: number;
  variant: MarkerVariant;
  /** Accessible name and hover tooltip, e.g. "GEO-TAM-003". */
  title: string;
  /** Short text inside the marker (e.g. an evidence count). */
  label?: string;
}

function icon(marker: MapMarker, selected: boolean): L.DivIcon {
  const size = selected ? 30 : marker.label ? 26 : 20;
  const label = marker.label ? `<span class="gt-marker__label">${marker.label.replace(/[^0-9+]/g, "")}</span>` : "";
  return L.divIcon({
    className: "gt-marker-host",
    html: `<span class="gt-marker gt-marker--${marker.variant}${selected ? " is-selected" : ""}">${label}</span>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
}

export function LeafletMap({ markers, selectedId, onSelect, fitKey, className, label, interactive = true, scrollZoom = true }: {
  markers: MapMarker[];
  selectedId?: string | null;
  onSelect?: (id: string | null) => void;
  /** Bounds are re-fitted whenever this changes (e.g. when filters change), not on every selection. */
  fitKey?: string;
  className?: string;
  label: string;
  interactive?: boolean;
  /** Off for small embedded maps, so scrolling the page never zooms the map by accident. */
  scrollZoom?: boolean;
}) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);
  const markerRefs = useRef(new Map<string, { leaflet: L.Marker; data: MapMarker }>());
  const selectRef = useRef(onSelect);
  selectRef.current = onSelect;
  const selectedRef = useRef(selectedId);
  selectedRef.current = selectedId;

  // Create the map once.
  useEffect(() => {
    if (!container.current) return;
    const map = L.map(container.current, {
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
      zoomControl: true,
      attributionControl: true,
      dragging: interactive,
      scrollWheelZoom: interactive && scrollZoom,
      doubleClickZoom: interactive,
      touchZoom: interactive,
      keyboard: interactive,
      worldCopyJump: true,
    });
    L.tileLayer(TILE_URL, { attribution: TILE_ATTRIBUTION, maxZoom: 19, crossOrigin: false }).addTo(map);
    map.attributionControl.setPrefix(false);
    layerRef.current = L.layerGroup().addTo(map);
    map.on("click", () => selectRef.current?.(null));
    mapRef.current = map;
    // The map must re-measure itself when the sidebar, a panel or the window changes size.
    const observer = new ResizeObserver(() => {
      map.invalidateSize({ pan: false });
      // Keep the selected tree visible when the layout changes (sidebar, rotation, panel).
      const selected = selectedRef.current ? markerRefs.current.get(selectedRef.current) : undefined;
      if (selected && !map.getBounds().contains(selected.leaflet.getLatLng())) map.panTo(selected.leaflet.getLatLng(), { animate: false });
    });
    observer.observe(container.current);
    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
      layerRef.current = null;
      markerRefs.current.clear();
    };
  }, [interactive, scrollZoom]);

  // Markers.
  useEffect(() => {
    const layer = layerRef.current;
    if (!layer) return;
    layer.clearLayers();
    markerRefs.current.clear();
    for (const data of markers) {
      const selected = data.id === selectedId;
      const leaflet = L.marker([data.lat, data.lng], {
        icon: icon(data, selected),
        title: data.title,
        alt: data.title,
        keyboard: interactive,
        riseOnHover: true,
        zIndexOffset: selected ? 1000 : 0,
        interactive,
      });
      leaflet.on("click", (event) => {
        L.DomEvent.stopPropagation(event);
        selectRef.current?.(data.id);
      });
      leaflet.on("keypress", (event: L.LeafletKeyboardEvent) => {
        if (event.originalEvent.key === "Enter" || event.originalEvent.key === " ") selectRef.current?.(data.id);
      });
      leaflet.addTo(layer);
      markerRefs.current.set(data.id, { leaflet, data });
    }
    // Selection is applied by the effect below; markers are rebuilt only when the data changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [markers, interactive]);

  // Selection: restyle in place and bring the selected marker into view.
  useEffect(() => {
    const map = mapRef.current;
    for (const [id, { leaflet, data }] of markerRefs.current) {
      const selected = id === selectedId;
      leaflet.setIcon(icon(data, selected));
      leaflet.setZIndexOffset(selected ? 1000 : 0);
    }
    const target = selectedId ? markerRefs.current.get(selectedId) : undefined;
    if (map && target) {
      const point = target.leaflet.getLatLng();
      if (!map.getBounds().pad(-0.15).contains(point)) map.panTo(point, { animate: true });
    }
  }, [selectedId, markers]);

  // Fit all markers when the marker set (filters) changes.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (markers.length === 0) {
      map.setView(DEFAULT_CENTER, DEFAULT_ZOOM);
    } else if (markers.length === 1) {
      map.setView([markers[0].lat, markers[0].lng], SINGLE_MARKER_ZOOM);
    } else {
      map.fitBounds(L.latLngBounds(markers.map((m) => [m.lat, m.lng] as [number, number])), { padding: [40, 40], maxZoom: FIT_MAX_ZOOM });
    }
    // Only when the set of markers changes, not on every re-render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey ?? markers.map((m) => m.id).join(",")]);

  return (
    <div
      ref={container}
      role="region"
      aria-label={label}
      // `isolate` keeps Leaflet's internal z-indexes below the app's header, drawers and dialogs.
      className={cn("gt-map isolate h-full w-full bg-sand", className)}
    />
  );
}
