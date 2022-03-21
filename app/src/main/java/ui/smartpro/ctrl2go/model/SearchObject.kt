package ui.smartpro.ctrl2go.model

import com.mapbox.geojson.Point
import com.mapbox.search.result.SearchResult

data class SearchObject(
   var results: List<SearchResult>
//   var name: String? = null,
//   var coordinate: Point?,
//   'Point{type=Point, bbox=null, coordinates=[27.40321159362793, 37.033790588378906]}'
//   var distanceMeters:String? = null
)
