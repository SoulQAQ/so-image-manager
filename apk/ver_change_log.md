## v0.3.2 (2026-05-12)

### 新增与修复
- 首页支持两种导入方式：按图集导入（系统图集列表，仅显示图集名称）/按图片导入（PhotoPicker）
- 图集导入后将该图集内图片批量加入 App
- 首页搜索升级：可按文件名、AI描述、AI标签、搜索词匹配图片
- 新增图片详情页：点击图片可放大查看并展示 AI 标注结果（描述、标签、状态）
- AI 标注结果回写到图片模型，支持详情与搜索联动
- 图标资源升级为 v2 资源名，规避部分机型图标缓存不刷新问题

---

# Image AI 鐗堟湰鍙樻洿鏃ュ織

## v0.3.1 (2026-05-12)

### 鏍稿績鍙樻洿

#### 鏉冮檺妯″瀷閲嶆瀯锛堥噸瑕侊級
- **绉婚櫎鎵€鏈夊獟浣撳簱鏉冮檺鐢宠** - 涓嶅啀鐢宠 READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE
- **鏀圭敤绯荤粺 PhotoPicker** - 鐢ㄦ埛閫氳繃绯荤粺閫夋嫨鍣ㄤ富鍔ㄩ€夋嫨鍥剧墖
- **鏈€灏忔潈闄愬師鍒?* - 搴旂敤浠呭鐞嗙敤鎴锋槑纭€夋嫨鐨勫浘鐗囷紝涓嶆壂鎻忓叏搴?- **闅愮鍙嬪ソ鏂囨** - 鏄庣‘鍛婄煡鐢ㄦ埛"搴旂敤浠呭鐞嗕綘涓诲姩閫夋嫨鐨勭収鐗?

#### 澶氶€塙I閲嶅仛
- **閫夋嫨鎬佷紭鍖?* - 閫変腑鏃跺浘鐗囪交寰缉鏀撅紙0.92锛夛紝鏇夸唬鍗婇€忔槑閬僵
- **鍕鹃€夋爣璁扮簿绠€** - 鍙充笂瑙?0dp灏忓渾鍦?鍕惧彿锛屾浛浠ｅぇ鍙稢heckCircle
- **AI鏍囨敞鎸夐挳** - 鏀逛负搴曢儴绱у噾鍨婼urface锛屾浛浠ｅ法澶х殑FilledIconButton
- **鎿嶄綔鏍忎紭鍖?* - 鍙栨秷/宸查€?鍏ㄩ€変笁鍖哄垎甯冿紝鏇存竻鐖?- **闀挎寜杩涘叆閫夋嫨妯″紡** - 淇濇寔鍘熸湁浜や簰閫昏緫
- **娣诲姞鏇村鍏ュ彛** - 缃戞牸鏈熬"+"鍗＄墖锛屾柟渚胯拷鍔犲浘鐗?
#### API Key娉ㄥ叆
- 浠?`env/apikey.txt` 璇诲彇API Key
- 閫氳繃 BuildConfig 娉ㄥ叆鍒版瀯寤轰骇鐗?- 鍚屾椂鏀寔 `local.properties` 涓殑 `aiApiKey` 灞炴€т綔涓哄閫?
#### 瀵艰埅绠€鍖?- 绉婚櫎鐩稿唽/璇︽儏椤碉紙AlbumScreen/ImageDetailScreen锛?- 涓荤晫闈㈢洿鎺ヤ綔涓哄浘鐗囬€夋嫨鍜屾爣娉ㄥ叆鍙?- 淇濈暀 WebView 鐢ㄤ簬楂樼骇绠＄悊鍔熻兘

### 鎶€鏈粏鑺?- 浣跨敤 `ActivityResultContracts.PickMultipleVisualMedia`
- 鏈€澶氭敮鎸佷竴娆￠€夋嫨20寮犲浘鐗?- AnimatedVisibility 瀹炵幇閫夋嫨妯″紡杩涘叆/閫€鍑哄姩鐢?- itemsIndexed 鐢ㄤ簬甯︾储寮曠殑缃戞牸閬嶅巻

---

## v0.3.0 (2026-05-12)

### 鏂板鍔熻兘

#### 鍘熺敓鐩稿唽UI
- 鐩稿唽鍒楄〃鐣岄潰锛圙alleryScreen锛? 鎸夋枃浠跺す鍒嗙被鏄剧ず鐩稿唽
- 鍥剧墖缃戞牸鐣岄潰锛圓lbumScreen锛? 鏄剧ず鍗曚釜鐩稿唽鍐呯殑鍥剧墖
- 鍥剧墖璇︽儏鐣岄潰锛圛mageDetailScreen锛? 澶у浘娴忚
- 澶氶€夋ā寮忔敮鎸?- 闀挎寜鍥剧墖杩涘叆閫夋嫨妯″紡锛屾敮鎸佸叏閫?
#### 涓婚绯荤粺
- 娴呰壊/鏆楄壊鍙屼富棰橀€傞厤
- 璁剧疆椤甸潰涓婚鍒囨崲锛堣窡闅忕郴缁?娴呰壊/鏆楄壊锛?- DataStore 瀛樺偍鐢ㄦ埛涓婚鍋忓ソ

#### 鍥炬爣涓庡搧鐗?- 浣跨敤 logo.png 浣滀负搴旂敤鍥炬爣
- 鍚勫昂瀵?mipmap 鍥炬爣鑷姩鐢熸垚

#### 鏉冮檺绠＄悊锛堝凡鍦?.3.1搴熷純锛?- ~~READ_MEDIA_IMAGES 鏉冮檺锛圓ndroid 13+锛墌~
- ~~READ_EXTERNAL_STORAGE 鏉冮檺锛圓ndroid 12鍙婁互涓嬶級~~
- ~~鏉冮檺璇锋眰寮曞鐣岄潰~~

### 鎶€鏈粏鑺?- Coil 鍥剧墖鍔犺浇搴撻泦鎴?- Material3 缁勪欢搴?- LazyVerticalGrid 缃戞牸甯冨眬
- Navigation Compose 璺敱閲嶆瀯
- GalleryViewModel/AlbumViewModel 鐘舵€佺鐞?
### 瀵艰埅鍙樻洿
- 榛樿棣栭〉鏀逛负鐩稿唽鍒楄〃锛坓allery锛?- 绉婚櫎鏃?HomeScreen锛堝崟鎸夐挳璺宠浆锛?- 淇濈暀 WebView 椤甸潰鐢ㄤ簬楂樼骇绠＄悊

---

## v0.2.0 (2026-05-12)

### 鏂板鍔熻兘

#### P1 鏁版嵁鍩虹灞?- Room 鏁版嵁搴撻厤缃笌鍒濆鍖?- 7涓狤ntity瀹氫箟锛欼mage, ImageAi, Tag, ImageTag, TagAlias, ImageQueryCache, ImageFeature
- 6涓狣AO鎺ュ彛锛欼mageDao, ImageAiDao, TagDao, ImageTagDao, SearchDao, ImageFeatureDao
- FTS5 鍏ㄦ枃妫€绱㈣櫄鎷熻〃鏀寔

#### P2 鍥剧墖鎺ュ叆灞?- MediaStore 鍥剧墖鎵弿鏈嶅姟
- 鍥剧墖棰勫鐞嗗櫒锛圫HA256璁＄畻銆佸帇缂┿€丒XIF鏃嬭浆绾犳锛?- 鍥剧墖鍏ラ槦绠＄悊锛堝幓閲嶃€佸叆搴擄級

#### P3 AI鍒嗘瀽灞?- OkHttp HTTP瀹㈡埛绔厤缃?- AI API璋冪敤灏佽锛堟敮鎸乂ision妯″瀷锛?- AI缁撴灉澶勭悊鍣紙JSON瑙ｆ瀽銆佹爣绛綾anonical鍖栥€佸琛ㄤ簨鍔″啓鍏ワ級

#### P4 鎼滅储妫€绱㈠眰
- FTS5 鏂囨湰妫€绱㈠疄鐜?- 鐪嬪浘鏌ユ爣绛撅紙搴撳唴鍥剧墖锛?- 鐪嬪浘鏌ユ爣绛撅紙澶栭儴涓存椂鍥剧墖+缂撳瓨锛?
#### P7 JSBridge鎵╁睍
鏂板鏂规硶锛?- `searchImages(query, limit)` - 鏂囨湰鎼滅储鍥剧墖
- `getImageTags(imageId)` - 鑾峰彇鍥剧墖鏍囩
- `saveUserTags(imageId, tagsJson)` - 淇濆瓨鐢ㄦ埛淇鏍囩
- `getAllTags()` - 鑾峰彇鎵€鏈夋爣绛?- `getTagAliases()` - 鑾峰彇鏍囩鍒悕鍒楄〃
- `addTagAlias(alias, canonical)` - 娣诲姞鏍囩鍒悕
- `deleteTagAlias(alias)` - 鍒犻櫎鏍囩鍒悕
- `getStatistics()` - 鑾峰彇鍥剧墖缁熻淇℃伅

#### P8 H5椤甸潰鎵╁睍
- Vue Router 璺敱閰嶇疆
- 棣栭〉瑙嗗浘锛圚omeView锛? 缁熻淇℃伅灞曠ず
- 鎼滅储椤甸潰锛圫earchView锛? 鏂囨湰鎼滅储鍥剧墖
- 鍥剧墖璇︽儏椤碉紙ImageDetailView锛? 鏍囩灞曠ず涓庣敤鎴蜂慨姝?- 鏍囩绠＄悊椤碉紙TagManagerView锛? 鍒悕绠＄悊

### 鍏朵粬鍙樻洿
- 鍖呭悕閲嶆瀯锛歚com.soul2.imageai` 鈫?`cn.soul2.imageai`
- 鏂板渚濊禆锛歊oom, OkHttp, Coroutines, DataStore, Vue Router

---

## v0.1.0 (2026-05-11)

### 鍒濆鐗堟湰
- Android 宸ョ▼楠ㄦ灦锛圞otlin + Compose锛?- H5 宸ョ▼锛圴ue3 + Vant4锛?- WebView 瀹瑰櫒涓庡畨鍏ㄩ厤缃?- JSBridge 鍩虹閫氫俊锛坧ing, getDeviceInfo锛?- Debug/Release APK 鎵撳寘

