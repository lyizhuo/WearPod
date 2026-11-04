(function () {
  'use strict';

  var MAX_IMPORT_BYTES = 5 * 1024 * 1024;
  var LANG = window.WEARPOD_LANG === 'en' ? 'en' : 'zh';
  // 导入进行中：按钮的 disabled 由「是否有文件」和「是否正在导入」共同决定，
  // 否则导入途中重选文件会把按钮重新点亮，发出第二个并发请求。
  var importing = false;

  // 文案随 App 语言：服务端把 window.WEARPOD_LANG 注入到本文件头部
  var COPY = {
    zh: {
      docTitle: 'WearPod 本地上传',
      heading: 'WearPod 本地上传',
      subtitle: '文件只保存在手表本地',
      uploadTitle: '上传 OPML',
      dropzone: '拖拽 OPML 到此处，或点击选择文件（.opml / .xml）',
      importAction: '导入',
      feedsTitle: '当前订阅（{n}）',
      empty: '暂无订阅',
      delete: '删除',
      doneAction: '完成',
      doneAlready: '已完成',
      finishing: '正在结束…',
      doneStatus: '已完成。现在可以关闭此页面。',
      doneHint: '完成后手表会显示结束页并关闭上传服务',
      importing: '正在导入…',
      imported: '已导入 {a} 个播客（跳过 {s} 个）',
      removed: '已删除该订阅',
      untitled: '未命名播客',
      errInvalid: '无法解析 OPML 文件',
      errTooLarge: '文件超过 5MB 上限',
      errNoFile: '请先选择文件',
      errBadRequest: '请求无效，请重试',
      errStore: '手表上的订阅数据已损坏，请重新导入 OPML',
      errNetwork: '连接失败，请确认手机与手表连接同一个 Wi-Fi'
    },
    en: {
      docTitle: 'WearPod Local Upload',
      heading: 'WearPod Local Upload',
      subtitle: 'Your subscriptions stay on this watch',
      uploadTitle: 'Upload OPML',
      dropzone: 'Drop an OPML file here, or tap to choose one (.opml / .xml)',
      importAction: 'Import',
      feedsTitle: 'Subscriptions ({n})',
      empty: 'No subscriptions yet',
      delete: 'Delete',
      doneAction: 'Done',
      doneAlready: 'Done',
      finishing: 'Finishing…',
      doneStatus: 'Done. You can close this page now.',
      doneHint: 'The watch will show its summary and stop the upload service.',
      importing: 'Importing…',
      imported: 'Import complete: {a} added, {s} skipped',
      removed: 'Subscription deleted',
      untitled: 'Untitled podcast',
      errInvalid: 'Couldn’t read that OPML file',
      errTooLarge: 'That file is larger than 5 MB',
      errNoFile: 'Choose a file first',
      errBadRequest: 'Something went wrong. Please try again',
      errStore: 'The subscription data on your watch could not be read. Import your OPML again.',
      errNetwork: 'Connection failed. Make sure your phone and watch are on the same Wi-Fi network'
    }
  };

  var T = COPY[LANG];

  var el = {};
  var selectedFile = null;
  var feedTotal = 0;
  var finishing = false;
  var completed = false;
  var pendingDeletes = 0;

  function $(id) {
    return document.getElementById(id);
  }

  function fmt(template, values) {
    return String(template).replace(/\{(\w+)\}/g, function (match, key) {
      return Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match;
    });
  }

  function applyCopy() {
    document.documentElement.lang = LANG === 'en' ? 'en' : 'zh-CN';
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute('data-i18n');
      if (T[key] !== undefined) {
        nodes[i].textContent = T[key];
      }
    }
    updateFeedsTitle();
    if (el.doneBtn) {
      syncDoneButton();
    }
  }

  function updateFeedsTitle() {
    el.feedsTitle.textContent = fmt(T.feedsTitle, { n: feedTotal });
  }

  function setStatus(text, kind) {
    el.status.textContent = text || '';
    el.status.className = 'status';
    if (kind === 'error') {
      el.status.classList.add('is-error');
    } else if (kind === 'ok') {
      el.status.classList.add('is-ok');
    }
  }

  function renderFilename() {
    el.filename.textContent = selectedFile ? selectedFile.name : '';
  }

  function syncImportButton() {
    el.importBtn.disabled = importing || !selectedFile || completed || finishing || pendingDeletes > 0;
  }

  function syncDoneButton() {
    if (!el.doneBtn) {
      return;
    }
    el.doneBtn.disabled = completed || finishing || importing || pendingDeletes > 0;
    el.doneBtn.textContent = completed ? T.doneAlready : (finishing ? T.finishing : T.doneAction);
  }

  async function requestJson(url, options) {
    var init = options || {};
    init.cache = 'no-store';
    var res = await fetch(url, init);
    var data = null;
    try {
      data = await res.json();
    } catch (e) {
      data = null;
    }
    return { res: res, data: data };
  }

  function createFeedItem(podcast) {
    var title = podcast && typeof podcast.title === 'string' && podcast.title
      ? podcast.title
      : T.untitled;
    var feedUrl = podcast && typeof podcast.feedUrl === 'string' ? podcast.feedUrl : '';

    var li = document.createElement('li');
    li.className = 'feed-item';

    var info = document.createElement('div');
    info.className = 'feed-info';

    var titleEl = document.createElement('p');
    titleEl.className = 'feed-title';
    titleEl.textContent = title;

    var urlEl = document.createElement('p');
    urlEl.className = 'feed-url';
    urlEl.textContent = feedUrl;

    info.appendChild(titleEl);
    info.appendChild(urlEl);

    var removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'btn btn-danger';
    removeBtn.textContent = T.delete;
    removeBtn.addEventListener('click', function () {
      removeFeed(feedUrl, removeBtn);
    });
    removeBtn.disabled = completed || finishing;

    li.appendChild(info);
    li.appendChild(removeBtn);
    return li;
  }

  function renderFeeds(podcasts) {
    while (el.feedList.firstChild) {
      el.feedList.removeChild(el.feedList.firstChild);
    }
    feedTotal = podcasts.length;
    updateFeedsTitle();
    el.feedEmpty.hidden = podcasts.length > 0;
    for (var i = 0; i < podcasts.length; i++) {
      el.feedList.appendChild(createFeedItem(podcasts[i]));
    }
  }

  async function loadFeeds() {
    try {
      var result = await requestJson('/api/feeds');
      var data = result.data;
      if (!result.res.ok || !data || data.ok !== true || !Array.isArray(data.podcasts)) {
        throw new Error('bad_response');
      }
      renderFeeds(data.podcasts);
    } catch (e) {
      setStatus(T.errNetwork, 'error');
    }
  }

  async function importSelectedFile() {
    if (completed || finishing) {
      return;
    }
    if (!selectedFile) {
      setStatus(T.errNoFile, 'error');
      return;
    }

    // 与服务器端上限一致：先本地拦一次，避免大文件传到一半才失败
    if (selectedFile.size > MAX_IMPORT_BYTES) {
      setStatus(T.errTooLarge, 'error');
      return;
    }

    importing = true;
    syncImportButton();
    syncDoneButton();
    setStatus(T.importing, null);

    try {
      var result = await requestJson('/api/import', {
        method: 'POST',
        headers: { 'Content-Type': selectedFile.type || 'application/xml' },
        body: selectedFile
      });
      var data = result.data;

      if (result.res.ok && data && data.ok === true) {
        var added = Number(data.added);
        var skipped = Number(data.skipped);
        if (!isFinite(added)) {
          added = 0;
        }
        if (!isFinite(skipped)) {
          skipped = 0;
        }
        selectedFile = null;
        el.fileInput.value = '';
        renderFilename();
        // 先刷列表再报结果：loadFeeds 失败时会写「连接失败」，不能盖掉导入结果。
        await loadFeeds();
        setStatus(fmt(T.imported, { a: added, s: skipped }), 'ok');
        return;
      }

      var code = data && typeof data.error === 'string' ? data.error : '';
      if (code === 'invalid_opml') {
        setStatus(T.errInvalid, 'error');
      } else if (code === 'too_large') {
        setStatus(T.errTooLarge, 'error');
      } else if (code === 'no_file') {
        setStatus(T.errNoFile, 'error');
      } else if (code === 'store_corrupt') {
        setStatus(T.errStore, 'error');
      } else {
        setStatus(T.errNetwork, 'error');
      }
    } catch (e) {
      setStatus(T.errNetwork, 'error');
    } finally {
      importing = false;
      syncImportButton();
      syncDoneButton();
    }
  }

  async function removeFeed(feedUrl, button) {
    if (!feedUrl || completed || finishing) {
      return;
    }

    pendingDeletes++;
    button.disabled = true;
    syncImportButton();
    syncDoneButton();
    try {
      var result = await requestJson('/api/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ feedUrls: [feedUrl] })
      });
      var data = result.data;

      if (result.res.ok && data && data.ok === true) {
        await loadFeeds();
        setStatus(T.removed, 'ok');
        return;
      }

      if (data && data.error === 'bad_request') {
        setStatus(T.errBadRequest, 'error');
      } else if (data && data.error === 'store_corrupt') {
        setStatus(T.errStore, 'error');
      } else {
        setStatus(T.errNetwork, 'error');
      }
    } catch (e) {
      setStatus(T.errNetwork, 'error');
    } finally {
      pendingDeletes--;
      button.disabled = completed || finishing;
      syncImportButton();
      syncDoneButton();
    }
  }

  function applyFile(file) {
    if (completed || finishing) {
      return;
    }
    selectedFile = file || null;
    renderFilename();
    syncImportButton();
    setStatus('', null);
  }

  function openPicker() {
    if (completed || finishing) {
      return;
    }
    el.fileInput.click();
  }

  function disablePageActions() {
    el.dropzone.classList.add('is-disabled');
    el.dropzone.setAttribute('aria-disabled', 'true');
    el.fileInput.disabled = true;
    el.importBtn.disabled = true;
    var buttons = el.feedList.querySelectorAll('button');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].disabled = true;
    }
  }

  async function finishSession() {
    if (finishing || completed) {
      return;
    }
    finishing = true;
    syncDoneButton();
    setStatus(T.finishing, null);

    try {
      var result = await requestJson('/api/complete', { method: 'POST' });
      var data = result.data;
      if (result.res.ok && data && data.ok === true) {
        completed = true;
        disablePageActions();
        syncImportButton();
        syncDoneButton();
        setStatus(T.doneStatus, 'ok');
        return;
      }
      setStatus(T.errBadRequest, 'error');
    } catch (e) {
      setStatus(T.errNetwork, 'error');
    } finally {
      finishing = false;
      syncDoneButton();
    }
  }

  function bindDropzone() {
    el.dropzone.addEventListener('click', openPicker);

    el.dropzone.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
        event.preventDefault();
        openPicker();
      }
    });

    el.dropzone.addEventListener('dragover', function (event) {
      event.preventDefault();
      el.dropzone.classList.add('is-over');
    });

    el.dropzone.addEventListener('dragleave', function () {
      el.dropzone.classList.remove('is-over');
    });

    el.dropzone.addEventListener('drop', function (event) {
      event.preventDefault();
      el.dropzone.classList.remove('is-over');
      if (completed || finishing) {
        return;
      }
      var files = event.dataTransfer ? event.dataTransfer.files : null;
      if (files && files.length > 0) {
        el.fileInput.value = '';
        applyFile(files[0]);
      }
    });
  }

  function init() {
    el.dropzone = $('dropzone');
    el.fileInput = $('file-input');
    el.importBtn = $('import-btn');
    el.status = $('status');
    el.filename = $('filename');
    el.feedList = $('feed-list');
    el.feedEmpty = $('feed-empty');
    el.feedsTitle = $('feeds-title');
    el.doneBtn = $('done-btn');

    applyCopy();
    bindDropzone();

    el.fileInput.addEventListener('change', function () {
      var files = el.fileInput.files;
      applyFile(files && files.length > 0 ? files[0] : null);
    });

    el.importBtn.addEventListener('click', importSelectedFile);
    el.doneBtn.addEventListener('click', finishSession);

    syncImportButton();
    syncDoneButton();
    loadFeeds();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
