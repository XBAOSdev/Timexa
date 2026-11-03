/*
 * i18n.js —— 内置网页的统一「语言 + 深浅色」适配层
 *
 * 三个职责，前两个必须在 <head> 里同步完成，不能等 DOM 建好再跑：
 *   1. 把 data-theme / data-lang 写到 <html> 上（避免先浅后深的闪变）；
 *   2. 简体以外的语言，按字典把静态界面文案替换掉；
 *   3. 暴露 window.AppI18n.t()，供页面脚本翻译**动态生成**的文案。
 *
 * ## 为什么静态文案用「原文做 key 的字典替换」
 * 页面文案是历史积累的，逐条加 data-i18n 属性既容易漏、又把 diff 弄得很大；
 * 用原文当 key 意味着简体（即页面原文）零成本，只需要维护另外两套表。
 *
 * ## 为什么动态文案要页面自己调 t()
 * DOM 遍历只在「加载完成」前后跑几次（DOMContentLoaded / load / +60ms / +400ms），
 * 而动态文案是渲染时才新建、甚至每秒重写的文本节点 —— 遍历根本追不上，
 * 而且下一次重写会把翻好的内容再冲掉。
 * 所以凡是 JS 里拼出来、带数字的句子，都由页面在渲染处调 t() 逐段翻译。
 *
 * 参数由安卓侧通过 URL query 传入：
 *   xxx.html?lang=zh-TW&theme=dark
 * 直接在浏览器里打开时会退化为「跟随浏览器语言 + 浅色」，便于单独调试。
 */
(function () {
    'use strict';

    var params = new URLSearchParams(window.location.search);

    /** 把各种语言标签归一到我们支持的三种之一。 */
    function normalize(tag) {
        var t = String(tag || '').toLowerCase();
        if (t.indexOf('zh') === 0) {
            var traditional = t.indexOf('tw') >= 0 || t.indexOf('hk') >= 0 ||
                t.indexOf('mo') >= 0 || t.indexOf('hant') >= 0;
            return traditional ? 'zh-TW' : 'zh-CN';
        }
        if (t.indexOf('en') === 0) return 'en';
        return 'zh-CN';
    }

    var lang = normalize(params.get('lang') || navigator.language || 'zh-CN');
    var theme = params.get('theme') === 'dark' ? 'dark' : 'light';

    var root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-lang', lang);

    var DICT = {
        'zh-TW': {
            '今日进度': '今日進度',
            '公历年度': '西元年度',
            '公历月度': '西元月度',
            '反应能力测试': '反應能力測試',
            '取消': '取消',
            '太阳情况': '太陽情況',
            '完整': '完整',
            '常用': '常用',
            '年龄趣味换算器': '年齡趣味換算器',
            '开始换算': '開始換算',
            '开始测试': '開始測試',
            '当绿色方块变成红色时，请尽快点击它。共测试3次，结束后将显示平均反应时间。':
                '當綠色方塊變成紅色時，請儘快點擊它。共測試 3 次，結束後將顯示平均反應時間。',
            '准备下一次测试...': '準備下一次測試…',
            '数据基于科学常识 + 趣味换算，仅供参考':
                '資料基於科學常識 + 趣味換算，僅供參考',
            '日出方位': '日出方位',
            '日落方位': '日落方位',
            '正在准备坐标…': '正在準備座標…',
            '改坐标': '改座標',
            '未获取到坐标': '未取得座標',
            '定位不可用时，可手动输入经纬度继续使用':
                '定位不可用時，可手動輸入經緯度繼續使用',
            '输入经纬度': '輸入經緯度',
            '修改后立即重新计算。': '修改後立即重新計算。',
            '输入坐标以计算当地的太阳数据。': '輸入座標以計算當地的太陽資料。',
            '未获取到定位坐标。你可以在系统设置里允许「大致位置」，或直接手动输入经纬度。':
                '未取得定位座標。你可以在系統設定裡允許「大致位置」，或直接手動輸入經緯度。',
            '纬度必须在 -90 ~ 90 之间': '緯度必須在 -90 ~ 90 之間',
            '经度必须在 -180 ~ 180 之间': '經度必須在 -180 ~ 180 之間',
            '日出': '日出',
            '日落': '日落',
            '正午太阳高度': '正午太陽高度',
            '昼长': '晝長',
            '北京时间': '北京時間',
            '当前太阳高度': '當前太陽高度',
            '当前方位角': '當前方位角',
            '出现在地方时 ': '出現在地方時 ',
            '日出至日落': '日出至日落',
            '极昼': '極晝',
            '极夜': '極夜',
            '太阳全天不落': '太陽全天不落',
            '太阳全天不升': '太陽全天不升',
            '太阳未升起': '太陽未升起',
            '无': '無',
            '0 小时': '0 小時',
            // 时长句子里用的单位。中文与单位列表同形，英文则必须用缩写（h / min），
            // 因此单独安排 key —— 同一个中文词在「单位列表」和「时长句子」里译法不同，
            // 若共用 '小时'/'分' 这两个 key，对象字面量里后写的会覆盖先写的。
            '时长单位时': '小時',
            '时长单位分': '分',
            // key 直接用完整简体短语，简体直通才正确
            '比北京时间快': '比北京時間快',
            '比北京时间慢': '比北京時間慢',
            '日出日落方位（以正北为 0°，顺时针）':
                '日出日落方位（以正北為 0°，順時針）',
            '当地地方时': '當地地方時',
            '与北京时间一致': '與北京時間一致',
            '太阳高度角与日出日落时刻按天文公式本地计算，未计入海拔与地形遮挡，结果仅供参考。':
                '太陽高度角與日出日落時刻按天文公式本機計算，未計入海拔與地形遮擋，結果僅供參考。',
            '坐标只在本机参与计算，不会上传。':
                '座標只在本機參與計算，不會上傳。',
            '来自系统定位': '來自系統定位',
            '手动输入坐标': '手動輸入座標',
            // 太阳高度状态（枚举文案）
            '接近天顶 · 阳光强烈': '接近天頂 · 陽光強烈',
            '高空 · 光照充足': '高空 · 光照充足',
            '斜射 · 光线偏暖': '斜射 · 光線偏暖',
            '贴近地平线 · 黄金时刻': '貼近地平線 · 黃金時刻',
            '民用曙暮光 · 天仍亮': '民用曙暮光 · 天仍亮',
            '天文曙暮光': '天文曙暮光',
            '极夜 · 太阳全天不升': '極夜 · 太陽全天不升',
            '夜间 · 太阳在地平线下': '夜間 · 太陽在地平線下',
            // 16 方位（枚举文案）
            '正北': '正北',
            '东北偏北': '東北偏北',
            '东北': '東北',
            '东北偏东': '東北偏東',
            '正东': '正東',
            '东南偏东': '東南偏東',
            '东南': '東南',
            '东南偏南': '東南偏南',
            '正南': '正南',
            '西南偏南': '西南偏南',
            '西南': '西南',
            '西南偏西': '西南偏西',
            '正西': '正西',
            '西北偏西': '西北偏西',
            '西北': '西北',
            '西北偏北': '西北偏北',
            '时间换算': '時間換算',
            // 时间单位名（枚举文案）
            '幺秒': '幺秒',
            '仄秒': '仄秒',
            '阿秒': '阿秒',
            '飞秒': '飛秒',
            '皮秒': '皮秒',
            '纳秒': '納秒',
            '微秒': '微秒',
            '毫秒': '毫秒',
            '秒': '秒',
            '刻': '刻',
            '小时': '小時',
            '分': '分',
            '时辰': '時辰',
            '天': '天',
            '候': '候',
            '周': '週',
            '旬': '旬',
            '月(朔望)': '月(朔望)',
            '月(公历)': '月(西曆)',
            '季度': '季度',
            '年(平年)': '年(平年)',
            '年(公历)': '年(西曆)',
            '年(儒略)': '年(儒略)',
            '年(闰年)': '年(閏年)',
            '年代': '年代',
            '世纪': '世紀',
            '千年': '千年',
            '时间进度条 · 世纪刻度': '時間進度條 · 世紀刻度',
            '本世纪进度': '本世紀進度',
            '本功能仅供参考': '本功能僅供參考',
            '本周进度': '本週進度',
            '本地计算，仅供参考': '本機計算，僅供參考',
            '本季度进度': '本季度進度',
            '本小时进度': '本小時進度',
            '确定查询': '確定查詢',
            '纬度（-90°~90°，北纬正，南纬负）': '緯度（-90°~90°，北緯正，南緯負）',
            '经度（-180°~180°，东经正，西经负）': '經度（-180°~180°，東經正，西經負）',
            '选择你的出生日期，每次都会生成不同的趣味换算结果～':
                '選擇你的出生日期，每次都會產生不同的趣味換算結果～'
        },
        'en': {
            '今日进度': 'Today',
            '公历年度': 'Calendar year',
            '公历月度': 'Calendar month',
            '反应能力测试': 'Reaction Test',
            '取消': 'Cancel',
            '太阳情况': 'Sun Info',
            '完整': 'Full',
            '常用': 'Common',
            '年龄趣味换算器': 'Fun Age Converter',
            '开始换算': 'Convert',
            '开始测试': 'Start test',
            '当绿色方块变成红色时，请尽快点击它。共测试3次，结束后将显示平均反应时间。':
                'When the green square turns red, tap it as fast as you can. 3 rounds; the average is shown at the end.',
            '准备下一次测试...': 'Get ready for the next round…',
            '数据基于科学常识 + 趣味换算，仅供参考':
                'Based on general knowledge plus a fun conversion. For reference only.',
            '日出方位': 'Sunrise bearing',
            '日落方位': 'Sunset bearing',
            '正在准备坐标…': 'Preparing coordinates…',
            '改坐标': 'Change',
            '未获取到坐标': 'No coordinates yet',
            '定位不可用时，可手动输入经纬度继续使用':
                'If location is unavailable, enter coordinates manually to continue',
            '输入经纬度': 'Enter coordinates',
            '修改后立即重新计算。': 'Changes are recalculated immediately.',
            '输入坐标以计算当地的太阳数据。':
                'Enter coordinates to compute the local sun data.',
            '未获取到定位坐标。你可以在系统设置里允许「大致位置」，或直接手动输入经纬度。':
                'No location fix available. Allow approximate location in system settings, or enter coordinates manually.',
            '纬度必须在 -90 ~ 90 之间': 'Latitude must be between -90 and 90',
            '经度必须在 -180 ~ 180 之间': 'Longitude must be between -180 and 180',
            '日出': 'Sunrise',
            '日落': 'Sunset',
            '正午太阳高度': 'Noon altitude',
            '昼长': 'Day length',
            '北京时间': 'Clock time (UTC+8)',
            '当前太阳高度': 'Sun altitude',
            '当前方位角': 'Sun azimuth',
            '出现在地方时 ': 'at local solar time ',
            '日出至日落': 'Sunrise to sunset',
            '极昼': 'Polar day',
            '极夜': 'Polar night',
            '太阳全天不落': 'The sun never sets',
            '太阳全天不升': 'The sun never rises',
            '太阳未升起': 'The sun is below the horizon',
            '无': 'None',
            // 「昼长 / 时间差」拼句子时逐段取用
            '0 小时': '0 h',
            // 时长用缩写，与下面单位列表里的全称（Hour / Minute）区分开，理由见简繁表同处的注释
            '时长单位时': 'h',
            '时长单位分': 'min',
            '比北京时间快': 'Ahead of clock time by',
            '比北京时间慢': 'Behind clock time by',
            '日出日落方位（以正北为 0°，顺时针）':
                'Sunrise and sunset bearings (0° = north, clockwise)',
            '当地地方时': 'Local solar time',
            '与北京时间一致': 'Same as clock time',
            '太阳高度角与日出日落时刻按天文公式本地计算，未计入海拔与地形遮挡，结果仅供参考。':
                'Sun altitude and sunrise/sunset times are computed locally with astronomical formulas, ignoring altitude and terrain. For reference only.',
            '坐标只在本机参与计算，不会上传。':
                'Coordinates are used for on-device calculation only and are never uploaded.',
            '来自系统定位': 'From device location',
            '手动输入坐标': 'Entered manually',
            // Sun altitude states
            '接近天顶 · 阳光强烈': 'Near zenith · intense sunlight',
            '高空 · 光照充足': 'High in the sky · plenty of light',
            '斜射 · 光线偏暖': 'Slanting rays · warmer light',
            '贴近地平线 · 黄金时刻': 'Near the horizon · golden hour',
            '民用曙暮光 · 天仍亮': 'Civil twilight · still bright',
            '天文曙暮光': 'Astronomical twilight',
            '极夜 · 太阳全天不升': 'Polar night · the sun never rises',
            '夜间 · 太阳在地平线下': 'Night · the sun below the horizon',
            // 16-point compass, spelled out to match the Chinese style
            '正北': 'North',
            '东北偏北': 'North-northeast',
            '东北': 'Northeast',
            '东北偏东': 'East-northeast',
            '正东': 'East',
            '东南偏东': 'East-southeast',
            '东南': 'Southeast',
            '东南偏南': 'South-southeast',
            '正南': 'South',
            '西南偏南': 'South-southwest',
            '西南': 'Southwest',
            '西南偏西': 'West-southwest',
            '正西': 'West',
            '西北偏西': 'West-northwest',
            '西北': 'Northwest',
            '西北偏北': 'North-northwest',
            '时间换算': 'Time Converter',
            // Time unit names
            '幺秒': 'Yoctosecond',
            '仄秒': 'Zeptosecond',
            '阿秒': 'Attosecond',
            '飞秒': 'Femtosecond',
            '皮秒': 'Picosecond',
            '纳秒': 'Nanosecond',
            '微秒': 'Microsecond',
            '毫秒': 'Millisecond',
            '秒': 'Second',
            '分': 'Minute',
            '刻': 'Ke (15 min)',
            '小时': 'Hour',
            '时辰': 'Shichen (2 h)',
            '天': 'Day',
            '候': 'Hou (5 days)',
            '周': 'Week',
            '旬': 'Xun (10 days)',
            '月(朔望)': 'Synodic month',
            '月(公历)': 'Calendar month',
            '季度': 'Quarter',
            '年(平年)': 'Common year (365 d)',
            '年(公历)': 'Calendar year',
            '年(儒略)': 'Julian year',
            '年(闰年)': 'Leap year',
            '年代': 'Decade',
            '世纪': 'Century',
            '千年': 'Millennium',
            '时间进度条 · 世纪刻度': 'Time Progress · Century scale',
            '本世纪进度': 'This century',
            '本功能仅供参考': 'For reference only',
            '本周进度': 'This week',
            '本地计算，仅供参考': 'Computed locally. For reference only.',
            '本季度进度': 'This quarter',
            '本小时进度': 'This hour',
            '确定查询': 'Query',
            '纬度（-90°~90°，北纬正，南纬负）':
                'Latitude (-90 to 90; north positive, south negative)',
            '经度（-180°~180°，东经正，西经负）':
                'Longitude (-180 to 180; east positive, west negative)',
            '选择你的出生日期，每次都会生成不同的趣味换算结果～':
                'Pick your date of birth; each conversion gives a different fun result.'
        }
    };

    // 简体就是页面原文，没有表 —— 于是 t() 天然退化成「原样返回」，零成本。
    var map = DICT[lang];

    /**
     * 翻译一个 key；查不到就原样返回。
     * 给**动态生成**的文案用（静态文案由下面的 DOM 遍历处理）。
     */
    function t(key) {
        return (map && map[key]) || key;
    }

    /*
     * 暴露给页面脚本。凡是「渲染时才拼出来、甚至每秒重写」的文案，
     * 都要在渲染处走 t()，否则 DOM 遍历追不上、也会被下次重写冲掉。
     */
    window.AppI18n = { lang: lang, theme: theme, t: t };

    if (!map) return;

    var SKIP_TAGS = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, CODE: 1, PRE: 1 };
    var ATTRS = ['placeholder', 'title', 'aria-label', 'alt', 'value'];

    function translateText() {
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) {
                var parent = node.parentNode;
                if (!parent || SKIP_TAGS[parent.nodeName]) return NodeFilter.FILTER_REJECT;
                return node.nodeValue && node.nodeValue.trim() ? NodeFilter.FILTER_ACCEPT
                    : NodeFilter.FILTER_REJECT;
            }
        });
        var node;
        while ((node = walker.nextNode())) {
            var raw = node.nodeValue;
            var key = raw.trim();
            var hit = map[key];
            // 保留原有的前后空白，避免破坏行内排版
            if (hit) node.nodeValue = raw.replace(key, hit);
        }
    }

    function translateAttributes() {
        var elements = document.body.querySelectorAll('[placeholder], [title], [aria-label], [alt], input[type="button"], input[type="submit"]');
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            for (var j = 0; j < ATTRS.length; j++) {
                var name = ATTRS[j];
                var current = el.getAttribute && el.getAttribute(name);
                if (!current) continue;
                var hit = map[current.trim()];
                if (hit) el.setAttribute(name, hit);
            }
        }
    }

    function run() {
        if (!document.body) return;
        try {
            translateText();
            translateAttributes();
        } catch (e) {
            // 翻译失败不能影响页面本身的可用性
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run);
    } else {
        run();
    }

    // 各页面的脚本会在加载后填充首屏内容（数字、标签），
    // 因此 load 之后再补两次，让首批动态渲染出来的静态标签也吃到翻译。
    window.addEventListener('load', function () {
        setTimeout(run, 60);
        setTimeout(run, 400);
    });
})();
