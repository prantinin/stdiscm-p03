/**
 * Media Upload Service - Browser GUI (Member 3)
 * Vanilla JavaScript implementation conforming to §6.2 and §6.3.
 */

(function () {
    'use strict';

    // State management
    let activePreviewCard = null;
    let activePreviewVideo = null;
    let currentVideosMap = new Map(); // id -> videoItem
    let isModalOpen = false;

    // DOM Elements
    const videoGrid = document.getElementById('video-grid');
    const emptyState = document.getElementById('empty-state');
    const videoCountBadge = document.getElementById('video-count-badge');
    const btnRefresh = document.getElementById('btn-refresh');

    // Status Bar DOM Elements
    const queueRatio = document.getElementById('queue-ratio');
    const queueBar = document.getElementById('queue-bar');
    const queueStatusText = document.getElementById('queue-status-text');
    const statC = document.getElementById('stat-c');
    const statSaved = document.getElementById('stat-saved');
    const statDropped = document.getElementById('stat-dropped');
    const statDuplicate = document.getElementById('stat-duplicate');

    // Modal DOM Elements
    const modal = document.getElementById('video-modal');
    const modalClose = document.getElementById('modal-close');
    const modalPlayer = document.getElementById('modal-player');
    const modalTitle = document.getElementById('modal-title');
    const modalProducer = document.getElementById('modal-producer');
    const modalSize = document.getElementById('modal-size');
    const modalDuration = document.getElementById('modal-duration');
    const modalUploaded = document.getElementById('modal-uploaded');
    const modalHash = document.getElementById('modal-hash');

    /**
     * Format byte size to readable units (KB, MB, GB).
     */
    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    /**
     * Format duration seconds to MM:SS.
     */
    function formatDuration(seconds) {
        if (!seconds || seconds <= 0) return '00:00';
        const totalSecs = Math.round(seconds);
        const mins = Math.floor(totalSecs / 60);
        const secs = totalSecs % 60;
        return String(mins).padStart(2, '0') + ':' + String(secs).padStart(2, '0');
    }

    /**
     * Format timestamp string to relative or localized time.
     */
    function formatTimestamp(isoString) {
        if (!isoString) return '';
        try {
            const date = new Date(isoString);
            return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        } catch (e) {
            return isoString;
        }
    }

    /**
     * Stop active hover preview. Restores thumbnail and pauses/resets preview video.
     */
    function stopActivePreview() {
        if (activePreviewVideo) {
            try {
                activePreviewVideo.pause();
                activePreviewVideo.removeAttribute('src'); // Stop downloading
                activePreviewVideo.load();
            } catch (e) {
                // Ignore cleanup errors
            }
            activePreviewVideo = null;
        }

        if (activePreviewCard) {
            activePreviewCard.classList.remove('preview-active');
            activePreviewCard = null;
        }
    }

    /**
     * Starts hover preview on a video card.
     * Guaranteed: Only one preview plays at a time. Previews are not preloaded.
     */
    function startHoverPreview(card, videoItem) {
        if (activePreviewCard === card) return;

        // Stop any currently playing preview first
        stopActivePreview();

        const mediaContainer = card.querySelector('.media-container');
        const previewVideo = card.querySelector('.preview-video');
        const fallbackLabel = card.querySelector('.preview-fallback-label');

        if (!previewVideo) return;

        activePreviewCard = card;
        activePreviewVideo = previewVideo;

        // Attach src only upon hover (No preloading per §6.3)
        const previewUrl = videoItem.preview_url || videoItem.previewUrl || ('/preview/' + encodeURIComponent(videoItem.id));
        previewVideo.src = previewUrl;
        previewVideo.muted = true;
        previewVideo.loop = true;

        // Fallback handling on error
        previewVideo.onerror = function () {
            if (activePreviewCard === card) {
                card.classList.remove('preview-active');
                if (fallbackLabel) {
                    fallbackLabel.style.display = 'block';
                }
            }
        };

        const playPromise = previewVideo.play();
        if (playPromise !== undefined) {
            playPromise.then(() => {
                if (activePreviewCard === card) {
                    card.classList.add('preview-active');
                    if (fallbackLabel) fallbackLabel.style.display = 'none';
                }
            }).catch(err => {
                // Autoplay policy or media decode error fallback
                if (activePreviewCard === card) {
                    card.classList.remove('preview-active');
                    if (fallbackLabel) fallbackLabel.style.display = 'block';
                }
            });
        }
    }

    /**
     * Opens modal player with full video seeking (/media/{id}).
     */
    function openFullVideoModal(videoItem) {
        // Stop any hover preview when opening modal
        stopActivePreview();

        const mediaUrl = videoItem.media_url || videoItem.mediaUrl || ('/media/' + encodeURIComponent(videoItem.id));

        // Use textContent for filename to safely escape output (§6.3)
        modalTitle.textContent = videoItem.name;
        modalProducer.textContent = videoItem.producer_id || videoItem.producerId || 'P1';
        modalSize.textContent = 'Size: ' + formatBytes(videoItem.size);
        modalDuration.textContent = 'Duration: ' + formatDuration(videoItem.duration);
        modalUploaded.textContent = 'Uploaded: ' + formatTimestamp(videoItem.uploaded_at || videoItem.uploadedAt);
        modalHash.textContent = videoItem.hash ? 'SHA-256: ' + videoItem.hash.substring(0, 16) + '...' : '';
        modalHash.title = videoItem.hash || '';

        // Configure video player
        modalPlayer.src = mediaUrl;
        modalPlayer.currentTime = 0;

        modal.classList.remove('hidden');
        isModalOpen = true;

        modalPlayer.play().catch(e => {
            console.log('Autoplay blocked or waiting for user interaction:', e.message);
        });
    }

    /**
     * Closes the full video modal and halts video download.
     */
    function closeModal() {
        if (!isModalOpen) return;
        modal.classList.add('hidden');
        isModalOpen = false;

        try {
            modalPlayer.pause();
            modalPlayer.removeAttribute('src'); // Stop network download
            modalPlayer.load();
        } catch (e) {
            // Ignore
        }
    }

    /**
     * Creates a DOM element for a video card safely using textContent for text fields.
     */
    function createVideoCardElement(video) {
        const card = document.createElement('div');
        card.className = 'video-card';
        card.setAttribute('data-id', video.id);

        const mediaContainer = document.createElement('div');
        mediaContainer.className = 'media-container';

        // Thumbnail image
        const thumbImg = document.createElement('img');
        thumbImg.className = 'thumbnail-img';
        thumbImg.alt = video.name;
        thumbImg.loading = 'lazy';
        thumbImg.src = video.thumbnail_url || video.thumbnailUrl || ('/thumb/' + encodeURIComponent(video.id));

        // Preview video element (src empty initially - no preload)
        const previewVideo = document.createElement('video');
        previewVideo.className = 'preview-video';
        previewVideo.playsInline = true;
        previewVideo.muted = true;
        previewVideo.loop = true;
        previewVideo.preload = 'none'; // strictly no preload until hover

        // Preview active badge indicator
        const previewBadge = document.createElement('span');
        previewBadge.className = 'preview-badge';
        previewBadge.textContent = 'Preview';

        // Broken preview fallback label
        const fallbackLabel = document.createElement('span');
        fallbackLabel.className = 'preview-fallback-label';
        fallbackLabel.textContent = 'Preview unavailable';

        // Duration badge
        const durationBadge = document.createElement('span');
        durationBadge.className = 'duration-badge';
        durationBadge.textContent = formatDuration(video.duration);

        // Producer badge
        const producerTag = document.createElement('span');
        producerTag.className = 'producer-tag';
        producerTag.textContent = video.producer_id || video.producerId || 'P1';

        mediaContainer.appendChild(thumbImg);
        mediaContainer.appendChild(previewVideo);
        mediaContainer.appendChild(previewBadge);
        mediaContainer.appendChild(fallbackLabel);
        mediaContainer.appendChild(durationBadge);
        mediaContainer.appendChild(producerTag);

        // Card info container
        const cardContent = document.createElement('div');
        cardContent.className = 'card-content';

        // Filename safely set via textContent
        const titleEl = document.createElement('div');
        titleEl.className = 'video-name';
        titleEl.textContent = video.name;
        titleEl.title = video.name;

        const metaRow = document.createElement('div');
        metaRow.className = 'video-meta-row';

        const sizeEl = document.createElement('span');
        sizeEl.textContent = formatBytes(video.size);

        const timeEl = document.createElement('span');
        timeEl.className = 'time-stamp';
        timeEl.textContent = formatTimestamp(video.uploaded_at || video.uploadedAt);

        metaRow.appendChild(sizeEl);
        metaRow.appendChild(timeEl);

        cardContent.appendChild(titleEl);
        cardContent.appendChild(metaRow);

        card.appendChild(mediaContainer);
        card.appendChild(cardContent);

        // Hover events for 10-second preview
        card.addEventListener('mouseenter', () => {
            startHoverPreview(card, video);
        });

        card.addEventListener('mouseleave', () => {
            if (activePreviewCard === card) {
                stopActivePreview();
            }
        });

        // Click event to open full video modal
        card.addEventListener('click', () => {
            openFullVideoModal(video);
        });

        return card;
    }

    /**
     * Render videos list into the gallery grid.
     * Updates smoothly without reloading page or interrupting an active preview!
     */
    function updateGallery(videos) {
        if (!videos || videos.length === 0) {
            emptyState.classList.remove('hidden');
            videoGrid.innerHTML = '';
            videoCountBadge.textContent = '0 videos';
            currentVideosMap.clear();
            return;
        }

        emptyState.classList.add('hidden');
        videoCountBadge.textContent = `${videos.length} video${videos.length === 1 ? '' : 's'}`;

        const newIdSet = new Set(videos.map(v => String(v.id)));

        // Remove cards that are no longer in catalog
        const existingCards = videoGrid.querySelectorAll('.video-card');
        existingCards.forEach(card => {
            const id = card.getAttribute('data-id');
            if (!newIdSet.has(id)) {
                if (activePreviewCard === card) {
                    stopActivePreview();
                }
                card.remove();
                currentVideosMap.delete(id);
            }
        });

        // Add or reorder cards
        // To maintain "newest first" order without disrupting active previews
        videos.forEach((video, index) => {
            const vidId = String(video.id);
            let existingCard = videoGrid.querySelector(`.video-card[data-id="${vidId}"]`);

            if (!existingCard) {
                // New card arrived
                const newCard = createVideoCardElement(video);
                if (index === 0) {
                    videoGrid.prepend(newCard);
                } else {
                    const referenceNode = videoGrid.children[index] || null;
                    videoGrid.insertBefore(newCard, referenceNode);
                }
            } else {
                // Card exists: if it's not in the right position, move it if not currently being previewed
                if (activePreviewCard !== existingCard) {
                    const currentPos = Array.prototype.indexOf.call(videoGrid.children, existingCard);
                    if (currentPos !== index) {
                        const targetNode = videoGrid.children[index] || null;
                        videoGrid.insertBefore(existingCard, targetNode);
                    }
                }
            }
            currentVideosMap.set(vidId, video);
        });
    }

    /**
     * Fetch video list from /api/videos
     */
    async function fetchVideos() {
        try {
            const res = await fetch('/api/videos');
            if (!res.ok) throw new Error(`HTTP error ${res.status}`);
            const videos = await res.json();
            updateGallery(videos);
        } catch (err) {
            console.warn('Failed to fetch videos from /api/videos:', err.message);
        }
    }

    /**
     * Fetch leaky-bucket and worker stats from /api/status
     */
    async function fetchStatus() {
        try {
            const res = await fetch('/api/status');
            if (!res.ok) throw new Error(`HTTP error ${res.status}`);
            const data = await res.json();

            const qLen = data.queue_length !== undefined ? data.queue_length : (data.queueLength || 0);
            const qCap = data.queue_capacity !== undefined ? data.queue_capacity : (data.queueCapacity || data.q || 5);
            const cVal = data.c !== undefined ? data.c : 2;
            const savedVal = data.saved_count !== undefined ? data.saved_count : (data.savedCount || 0);
            const droppedVal = data.dropped_count !== undefined ? data.dropped_count : (data.droppedCount || 0);
            const duplicateVal = data.duplicate_count !== undefined ? data.duplicate_count : (data.duplicateCount || 0);

            // Update DOM text values
            queueRatio.textContent = `${qLen} / ${qCap}`;
            statC.textContent = cVal;
            statSaved.textContent = savedVal;
            statDropped.textContent = droppedVal;
            statDuplicate.textContent = duplicateVal;

            // Update Progress Bar
            const percentage = qCap > 0 ? Math.min(100, Math.round((qLen / qCap) * 100)) : 0;
            queueBar.style.width = `${percentage}%`;

            queueBar.classList.remove('warning', 'full');
            if (percentage >= 100) {
                queueBar.classList.add('full');
                queueStatusText.textContent = 'Queue FULL (Leaky-Bucket drops active)';
                queueStatusText.style.color = '#ef4444';
            } else if (percentage >= 70) {
                queueBar.classList.add('warning');
                queueStatusText.textContent = 'Queue filling rapidly';
                queueStatusText.style.color = '#f59e0b';
            } else {
                queueStatusText.textContent = `${qCap - qLen} slot${(qCap - qLen) === 1 ? '' : 's'} available`;
                queueStatusText.style.color = 'var(--text-muted)';
            }
        } catch (err) {
            console.warn('Failed to fetch status from /api/status:', err.message);
        }
    }

    /**
     * Unified polling loop (every 2 seconds)
     */
    async function poll() {
        await Promise.all([fetchVideos(), fetchStatus()]);
    }

    // Modal Close Listeners
    modalClose.addEventListener('click', closeModal);
    modal.addEventListener('click', (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });

    window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' || e.key === 'Esc') {
            closeModal();
            stopActivePreview();
        }
    });

    btnRefresh.addEventListener('click', () => {
        poll();
    });

    // Initial load and start 2-second polling timer
    poll();
    setInterval(poll, 2000);

})();
