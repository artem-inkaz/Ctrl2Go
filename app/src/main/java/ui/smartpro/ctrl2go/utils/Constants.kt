package ui.smartpro.ctrl2go.utils

import com.mapbox.search.result.SearchResult
import ui.smartpro.ctrl2go.model.SearchObject as SearchObject1

class DataConstants {

    companion object {
        var searchObject: SearchObject1? = SearchObject1(mutableListOf<SearchResult>())
    }
}