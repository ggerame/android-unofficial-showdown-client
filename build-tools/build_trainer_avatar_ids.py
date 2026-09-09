# -*- coding: utf-8 -*-

import re
from json import dumps
from common import *

app_data_dir = "../psclient/src/main/res/raw"
target_file_name = "trainer_avatar_ids.json"
url = "https://raw.githubusercontent.com/smogon/pokemon-showdown-client/master/play.pokemonshowdown.com/src/battle-dex-data.ts"

data = get_remote_data(url)
start = data.index("export const BattleAvatarNumbers")
block = data[start:]
block = block[block.index("{") + 1:block.index("};")]

avatars = {}
entry = re.compile(r"^\s*(?:(\d+)|'([^']+)'|([A-Za-z0-9_-]+)):\s*'([^']+)',", re.MULTILINE)
for match in entry.finditer(block):
    key = match.group(1) or match.group(2) or match.group(3)
    avatars[key] = match.group(4)

expected = {"1": "lucas", "7": "bugcatcher-gen4dp", "190": "elesa",
            "#bw2elesa": "elesa-gen5bw2", "1001": "#1001"}
if any(avatars.get(key) != value for key, value in expected.items()):
    raise ValueError("BattleAvatarNumbers format changed upstream")

write_into_file(app_data_dir + "/" + target_file_name,
                dumps(avatars, separators=(",", ":")))

finish()
