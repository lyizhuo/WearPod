(function () {
  'use strict';

  var MAX_IMPORT_BYTES = 5 * 1024 * 1024;
  var MESSAGES = {
    invalid_opml: '无法解析 OPML 文件',
    too_large: '文件超过 5MB 上限',
    no_file: '请先选择文件',
    bad_request: '请求无效，请重试',
    network: '连接失败，请确认仍在手表热点下'
  };

  var el = {};
  var selectedFile = null;

  function $(id) {
    return document.getElementById(id);
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
    el.importBtn.disabled = !selectedFile;
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
      : '未命名播客';
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
    removeBtn.textContent = '删除';
    removeBtn.addEventListener('click', function () {
      removeFeed(feedUrl, removeBtn);
    });

    li.appendChild(info);
    li.appendChild(removeBtn);
    return li;
  }

  function renderFeeds(podcasts) {
    while (el.feedList.firstChild) {
      el.feedList.removeChild(el.feedList.firstChild);
    }
    el.feedCount.textContent = String(podcasts.length);
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
      setStatus(MESSAGES.network, 'error');
    }
  }

  async function importSelectedFile() {
    if (!selectedFile) {
      setStatus(MESSAGES.no_file, 'error');
      return;
    }

    // 与服务器端上限一致：先本地拦一次，避免大文件传到一半才失败
    if (selectedFile.size > MAX_IMPORT_BYTES) {
      setStatus(MESSAGES.too_large, 'error');
      return;
    }

    el.importBtn.disabled = true;
    setStatus('正在导入…', null);

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
        setStatus('已导入 ' + added + ' 个播客（跳过 ' + skipped + ' 个）', 'ok');
        selectedFile = null;
        el.fileInput.value = '';
        renderFilename();
        await loadFeeds();
        return;
      }

      var code = data && typeof data.error === 'string' ? data.error : '';
      if (code === 'invalid_opml') {
        setStatus(MESSAGES.invalid_opml, 'error');
      } else if (code === 'too_large') {
        setStatus(MESSAGES.too_large, 'error');
      } else if (code === 'no_file') {
        setStatus(MESSAGES.no_file, 'error');
      } else {
        setStatus(MESSAGES.network, 'error');
      }
    } catch (e) {
      setStatus(MESSAGES.network, 'error');
    } finally {
      syncImportButton();
    }
  }

  async function removeFeed(feedUrl, button) {
    if (!feedUrl) {
      return;
    }

    button.disabled = true;
    try {
      var result = await requestJson('/api/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ feedUrls: [feedUrl] })
      });
      var data = result.data;

      if (result.res.ok && data && data.ok === true) {
        setStatus('已删除该订阅', 'ok');
        await loadFeeds();
        return;
      }

      if (data && data.error === 'bad_request') {
        setStatus(MESSAGES.bad_request, 'error');
      } else {
        setStatus(MESSAGES.network, 'error');
      }
    } catch (e) {
      setStatus(MESSAGES.network, 'error');
    } finally {
      button.disabled = false;
    }
  }

  function applyFile(file) {
    selectedFile = file || null;
    renderFilename();
    syncImportButton();
    setStatus('', null);
  }

  function openPicker() {
    el.fileInput.click();
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
    el.feedCount = $('feed-count');

    bindDropzone();

    el.fileInput.addEventListener('change', function () {
      var files = el.fileInput.files;
      applyFile(files && files.length > 0 ? files[0] : null);
    });

    el.importBtn.addEventListener('click', importSelectedFile);

    syncImportButton();
    loadFeeds();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
