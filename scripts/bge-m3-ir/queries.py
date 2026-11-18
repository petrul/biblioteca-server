"""The judged queries of the BGE-M3 IR prototype.

`relevant` decides, for any passage, whether it is about the query's topic:
a regex over the passage text (Romanian, French, English forms). For the
paraphrase (2) and English (6) queries the regex names words the query does
NOT contain, so keyword matching alone cannot find them - only meaning can.
`lucene` is the keyword query used to collect candidate passages.
"""
import re

QUERIES = [
    dict(id="cumani", q="Cumani și pecenegi", lucene="cumani pecenegi",
         relevant=r"\b(cuman|pecene[gz]|pacinac|polov[tț])"),
    # any steppe people, named or described - the query names none of them
    dict(id="nomazi", q="popoarele nomade din stepă care atacau Bizanțul", lucene=None,
         relevant=r"\b(cuman|pecene[gz]|pacinac|polov[tț]|avar|huni?\b|hunnic|t[aă]tar|mongol|nomad)"),
    dict(id="mihai", q="uciderea lui Mihai Viteazul", lucene="Mihai Viteazul Turda ucis",
         relevant=r"(?s)(?=.*\bMihai\b)(?=.*(ucis|ucider|omor|Turda|\bBasta\b|tăiat|taiat|moartea|capul))"),
    dict(id="borodino", q="bătălia de la Borodino", lucene="Borodino",
         relevant=r"Borodino"),
    dict(id="ciuma", q="epidemia de ciumă", lucene="ciuma ciumă epidemie",
         relevant=r"\b(cium[aăe]|ciumi)"),
    dict(id="plague", q="the plague epidemic", lucene=None,
         relevant=r"\b(cium[aăe]|ciumi|plague|pestilen)"),
]

def is_relevant(query: dict, text: str) -> bool:
    return re.search(query["relevant"], text, re.IGNORECASE) is not None
