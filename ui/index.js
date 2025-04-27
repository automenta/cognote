import * as THREE from 'three';
import {CSS3DObject, CSS3DRenderer} from 'three/addons/renderers/CSS3DRenderer.js';
import {gsap} from "gsap";

const $ = (x) => document.querySelector(x);
const $$ = (x) => document.querySelectorAll(x);

const clamp = (val, min, max) => Math.max(min, Math.min(max, val));
const lerp = (a, b, t) => a + (b - a) * t;
const generateId = (prefix) => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
const DEG2RAD = Math.PI / 180;

class MindMap {
    nodes = new Map();
    edges = new Map();

    nodeSelected = null;
    edgeSelected = null;

    isLinking = false;
    linkSourceNode = null;
    tempLinkLine = null;

    ui = null;
    camera = null;
    _cam = null;
    layout = null;

    renderGL = null;
    renderHTML = null;

    constructor(container) {
        this.container = container;
        this.scene = new THREE.Scene();

        this.cssScene = new THREE.Scene();

        this._cam = new THREE.PerspectiveCamera(70, window.innerWidth / window.innerHeight, 1, 20000);
        this._cam.position.z = 700;

        this.camera = new Camera(this);

        this.renderGL = new THREE.WebGLRenderer({canvas: $('#webgl-canvas'), antialias: true, alpha: true});
        this.renderGL.setSize(window.innerWidth, window.innerHeight);
        this.renderGL.setPixelRatio(window.devicePixelRatio);
        this.renderGL.setClearColor(0x000000, 0); // Transparent background

        this.renderHTML = new CSS3DRenderer();
        this.renderHTML.setSize(window.innerWidth, window.innerHeight);

        const css3dContainer = $('#css3d-container'); //TODO dynamically create this, don't rely on it already present
        css3dContainer.appendChild(this.renderHTML.domElement);

        const ambientLight = new THREE.AmbientLight(0xffffff, 0.8);
        this.scene.add(ambientLight);
        const directionalLight = new THREE.DirectionalLight(0xffffff, 0.7);
        directionalLight.position.set(0.5, 1, 0.75);
        this.scene.add(directionalLight);

        this.centerView(null, 0); // Center immediately
        this.camera.setInitialState(); // Set initial state AFTER first positioning

        window.addEventListener('resize', this.onWindowResize.bind(this), false);
    }

    addNode(n) {
        if (!n.id) n.id = generateId('node');
        if (this.nodes.has(n.id)) return this.nodes.get(n.id);

        this.nodes.set(n.id, n);
        n.mindMap = this;
        if (n.cssObject) this.cssScene.add(n.cssObject);
        this.layout?.addNode(n);
        // UIManager setup moved to initialization to ensure all elements exist
        return n;
    }

    removeNode(nodeId) {
        const node = this.nodes.get(nodeId);
        if (!node) return;
        if (this.nodeSelected === node) this.setSelectedNode(null);
        if (this.linkSourceNode === node) this.ui?.cancelLinking();

        const edgesToRemove = [...this.edges.values()].filter(edge => edge.source === node || edge.target === node);
        edgesToRemove.forEach(edge => this.removeEdge(edge.id));

        node.dispose();
        this.nodes.delete(nodeId);
        this.layout?.removeNode(node);
    }

    addEdge(sourceNode, targetNode, data = {}) {
        if (!sourceNode || !targetNode || sourceNode === targetNode) return null;
        if ([...this.edges.values()].some(e => (e.source === sourceNode && e.target === targetNode) || (e.source === targetNode && e.target === sourceNode))) return null;

        const edgeId = generateId('edge');
        const edge = new Edge(edgeId, sourceNode, targetNode, data);
        this.edges.set(edgeId, edge);
        if (edge.geo) this.scene.add(edge.geo);
        this.layout?.addEdge(edge);
        return edge;
    }

    removeEdge(edgeId) {
        const edge = this.edges.get(edgeId);
        if (!edge) return;
        if (this.edgeSelected === edge) this.setSelectedEdge(null); // Deselect before removing
        edge.dispose();
        this.edges.delete(edgeId);
        this.layout?.removeEdge(edge);
    }

    getNodeById = id => this.nodes.get(id);
    getEdgeById = id => this.edges.get(id);

    update() {
        this.nodes.forEach(node => node.update(this));
        this.edges.forEach(edge => edge.update(this));
        this.ui?.updateEdgeMenuPosition();
    }

    render() {
        this.renderGL.render(this.scene, this._cam);
        this.renderHTML.render(this.cssScene, this._cam);
    }

    onWindowResize() {
        const iw = window.innerWidth, ih = window.innerHeight;
        this._cam.aspect = iw / ih;
        this._cam.updateProjectionMatrix();
        this.renderGL.setSize(iw, ih);
        this.renderHTML.setSize(iw, ih);
    }

    centerView(targetPosition = null, duration = 0.7) {
        let targetPos;
        if (targetPosition) {
            targetPos = targetPosition.clone();
        } else {
            if (this.nodes.size === 0)
                targetPos = new THREE.Vector3(0, 0, 0);
            else {
                targetPos = new THREE.Vector3();
                this.nodes.forEach(node => targetPos.add(node.position));
                targetPos.divideScalar(this.nodes.size || 1); // Avoid division by zero
            }
        }
        const distance = this.nodes.size > 1 ? 700 : 400;
        this.camera.moveTo(targetPos.x, targetPos.y, targetPos.z + distance, duration, targetPos);
    }

    focusOnNode(node, duration = 0.6, pushHistory = false) {
        if (!node || !this._cam) return;
        const targetPos = node.position.clone();

        const fov = this._cam.fov * DEG2RAD;
        const aspect = this._cam.aspect;
        const nodeSize = Math.max(node.size.width / aspect, node.size.height) * 1.2; // Add 20% padding
        const distance = nodeSize / (2 * Math.tan(fov / 2)) + 50; // Add min distance

        if (pushHistory) this.camera.pushState();
        this.camera.moveTo(targetPos.x, targetPos.y, targetPos.z + distance, duration, targetPos);
    }

    autoZoom(node) {
        if (!node || !this.camera) return;
        const currentTargetNodeId = this.camera.getCurrentTargetNodeId();
        if (currentTargetNodeId === node.id) {
            this.camera.popState();
        } else {
            this.camera.pushState();
            this.camera.setCurrentTargetNodeId(node.id);
            this.focusOnNode(node, 0.6, false); // History already pushed
        }
    }

    screen2world(screenX, screenY, targetZ = 0) {
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(new THREE.Vector3(
            (screenX / window.innerWidth) * +2 - 1,
            (screenY / window.innerHeight) * -2 + 1,
            0.5), this._cam);
        const intersectPoint = new THREE.Vector3();
        return raycaster.ray.intersectPlane(
            new THREE.Plane(new THREE.Vector3(0, 0, 1), -targetZ), intersectPoint)
            ? intersectPoint : null;
    }

    setSelectedNode(node) {
        if (this.nodeSelected === node) return;
        this.nodeSelected?.htmlElement?.classList.remove('selected');
        this.nodeSelected = node;
        this.nodeSelected?.htmlElement?.classList.add('selected');
        if (node) this.setSelectedEdge(null); // Deselect edge when selecting node
    }

    setSelectedEdge(edge) {
        if (this.edgeSelected === edge) return;
        this.edgeSelected?.setHighlight(false);
        this.ui?.hideEdgeMenu(); // Hide previous menu
        this.edgeSelected = edge;
        this.edgeSelected?.setHighlight(true);
        if (edge) {
            this.setSelectedNode(null); // Deselect node when selecting edge
            this.ui?.showEdgeMenu(edge);
        }
    }

    intersectedObject(screenX, screenY) {
        const vec = new THREE.Vector2((screenX / window.innerWidth) * 2 - 1, -(screenY / window.innerHeight) * 2 + 1);
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(vec, this._cam);
        raycaster.params.Line.threshold = 5; // Increase threshold for line picking

        const edgeObjects = [...this.edges.values()].map(e => e.threeObject).filter(Boolean);
        if (edgeObjects.length === 0) return null;

        const intersects = raycaster.intersectObjects(edgeObjects);

        if (intersects.length > 0) {
            const intersectedLine = intersects[0].object;
            return [...this.edges.values()].find(edge => edge.threeObject === intersectedLine);
        }
        return null; // No edge intersected
    }

    animate() {
        const frame = () => {

            // Layout step handled by forceLayout.start() -> requestAnimationFrame
            this.update(); // Update node/edge visuals based on positions

            // Camera update handled by cameraController.update() -> requestAnimationFrame
            this.render();

            requestAnimationFrame(frame); // Keep main render loop separate
        };
        frame();
    }
}

class Node {
    mindMap = null;
    htmlElement = null;
    cssObject = null;
    position = new THREE.Vector3();
    size = {width: 160, height: 70};
    data = {contentScale: 1};
    billboard = true;

    constructor(id, position = {x: 0, y: 0, z: 0}, data = {}) {
        this.id = id ?? generateId('node'); // Ensure ID exists
        this.position.set(position.x, position.y, position.z);
        this.data = {...this.data, ...data};
        this.size.width = data.width ?? this.size.width;
        this.size.height = data.height ?? this.size.height;
        this.htmlElement = this._createElement();
        this.cssObject = new CSS3DObject(this.htmlElement);
        this.update();
        this.setContentScale(this.data.contentScale); // Apply initial scale
        if (this.data.backgroundColor) {
            this.htmlElement.style.setProperty('--node-bg', this.data.backgroundColor);
        }
    }

    _createElement() {
        const el = document.createElement('div');
        el.className = 'node-html';
        el.id = `node-html-${this.id}`;
        el.dataset.nodeId = this.id;
        el.style.width = `${this.size.width}px`;
        el.style.height = `${this.size.height}px`;

        el.innerHTML = `
              <div class="node-inner-wrapper">
                  <div class="node-content" spellcheck="false" style="transform: scale(${this.data.contentScale});">${this.data.label || ''}</div>
                  <div class="node-controls">
                      <button class="node-quick-button node-content-zoom-in" title="Zoom In Content (+)">+</button>
                      <button class="node-quick-button node-content-zoom-out" title="Zoom Out Content (-)">-</button>
                      <button class="node-quick-button node-grow" title="Grow Node (Ctrl++)">➚</button>
                      <button class="node-quick-button node-shrink" title="Shrink Node (Ctrl+-)">➘</button>
                      <button class="node-quick-button delete-button node-delete" title="Delete Node (Del)">×</button>
                  </div>
              </div>
              <div class="resize-handle" title="Resize Node"></div>
          `;
        return el;
    }

    setPosition(x, y, z) {
        this.position.set(x, y, z);
    }

    setSize(width, height, scaleContent = false) {
        const oldWidth = this.size.width;
        const oldHeight = this.size.height;
        this.size.width = Math.max(80, width);
        this.size.height = Math.max(40, height);
        if (this.htmlElement) {
            this.htmlElement.style.width = `${this.size.width}px`;
            this.htmlElement.style.height = `${this.size.height}px`;
        }
        if (scaleContent && oldWidth > 0 && oldHeight > 0) {
            const scaleFactor = Math.sqrt((this.size.width * this.size.height) / (oldWidth * oldHeight));
            this.setContentScale(this.data.contentScale * scaleFactor);
        }
        this.mindMap?.layoutEngine?.kick();
    }

    setContentScale(scale) {
        this.data.contentScale = clamp(scale, 0.3, 3.0);
        const contentEl = this.htmlElement?.querySelector('.node-content');
        if (contentEl) {
            contentEl.style.transform = `scale(${this.data.contentScale})`;
        }
    }

    adjustContentScale(delta) {
        this.setContentScale(this.data.contentScale * (1 + delta));
    }

    adjustNodeSize(factor) {
        this.setSize(this.size.width * factor, this.size.height * factor, false); // Don't auto-scale content here
    }

    update(mindMap) {
        if (this.cssObject) {
            this.cssObject.position.copy(this.position);
            if (mindMap && this.billboard) {
                //TODO 'Billboarding': Face camera slightly (can cause jittering if layout is active)
                //this.cssObject?.rotation.copy(mindMap.camera.rotation);
            }
        }
    }

    dispose() {
        this.htmlElement?.remove();
        this.cssObject?.parent?.remove(this.cssObject);
    }

    startDrag() {
        this.htmlElement?.classList.add('dragging');
        this.mindMap?.layoutEngine?.fixNode(this);
    }

    drag(newPosition) {
        this.setPosition(newPosition.x, newPosition.y, newPosition.z);
        this.update();
    }

    endDrag() {
        this.htmlElement?.classList.remove('dragging');
        this.mindMap?.layoutEngine?.releaseNode(this);
        this.mindMap?.layoutEngine?.kick();
    }

    startResize() {
        this.htmlElement?.classList.add('resizing');
        this.mindMap?.layoutEngine?.fixNode(this);
    }

    resize(newWidth, newHeight) {
        this.setSize(newWidth, newHeight);
    }

    endResize() {
        this.htmlElement?.classList.remove('resizing');
        this.mindMap?.layoutEngine?.releaseNode(this);
    }
}

class NoteNode extends Node {
    constructor(id, pos = {x: 0, y: 0, z: 0}, data = {content: ''}) {
        super(id, pos, {...data, type: 'note', label: data.content}); // Use content as label for HTML
        this._initRichText();
    }

    _initRichText() {
        this.htmlElement.classList.add('note-node');
        const c = this.htmlElement.querySelector('.node-content');
        if (c) {
            c.contentEditable = "true";
            c.innerHTML = this.data.label || ''; // Use label which holds the content
            let debounceTimer;
            c.addEventListener('input', () => {
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(() => {
                    this.data.label = c.innerHTML;
                }, 300);
            });
            c.addEventListener('mousedown', e => e.stopPropagation());
            c.addEventListener('touchstart', e => e.stopPropagation(), {passive: true});
            c.addEventListener('wheel', e => { if (c.scrollHeight > c.clientHeight) e.stopPropagation();  /* Allow scrolling inside content */ }, {passive: false});
        }
    }
}

class Edge {
    geo = null;
    data = {color: 0x00d0ff, thickness: 1.5, style: 'solid', constraint: 'normal'}; // Default visual/layout data

    constructor(id, sourceNode, targetNode, data = {}) {
        this.id = id;
        this.source = sourceNode;
        this.target = targetNode;
        this.data = {...this.data, ...data};
        this.geo = this._initGeo();
        this.update();
    }

    _initGeo() {
        const material = new THREE.LineBasicMaterial({
            color: this.data.color,
            linewidth: this.data.thickness, // Note: limited support
            transparent: true,
            opacity: 0.6,
            depthTest: false, // Render edges slightly on top
        });
        const points = [this.source.position.clone(), this.target.position.clone()];
        const geometry = new THREE.BufferGeometry().setFromPoints(points);
        const line = new THREE.Line(geometry, material);
        line.renderOrder = -1; // Render before nodes if depthTest is true
        line.userData.edgeId = this.id; // Link back to edge object
        return line;
    }

    update(mindMap) {
        if (!this.geo || !this.source || !this.target) return;
        const positions = this.geo.geometry.attributes.position;
        positions.setXYZ(0, this.source.position.x, this.source.position.y, this.source.position.z);
        positions.setXYZ(1, this.target.position.x, this.target.position.y, this.target.position.z);
        positions.needsUpdate = true;
        this.geo.geometry.computeBoundingSphere();
    }

    setHighlight(highlight) {
        if (!this.geo) return;
        let m = this.geo.material;
        m.opacity = highlight ? 1.0 : 0.6;
        m.color.set(highlight ? 0x00ffff : this.data.color);
        // Consider Line2 for thickness change on highlight if needed
        // this.threeObject.material.linewidth = highlight ? this.data.thickness * 1.5 : this.data.thickness;
        m.needsUpdate = true;
    }

    dispose() {
        if (this.geo) {
            this.geo.geometry?.dispose();
            this.geo.material?.dispose();
            this.geo.parent?.remove(this.geo);
            this.geo = null; // Clear reference
        }
    }
}

class UIManager {
    draggedNode = null;
    resizedNode = null;

    hoveredEdge = null;
    edgeMenu = null; // Will hold the CSS3DObject wrapper

    resizeStartPos = {x: 0, y: 0};
    resizeStartSize = {width: 0, height: 0};
    dragOffset = new THREE.Vector3();

    isMouseDown = false;
    isRightMouseDown = false; // Track right mouse specifically for autozoom cancel
    potentialClick = true;
    lastPointerPos = {x: 0, y: 0};

    confirmCallback = null;

    constructor(mindMap) {
        this.mindMap = mindMap;
        this.container = mindMap.container;
        this.contextMenu = $('#context-menu');
        this.confirmDialog = $('#confirm-dialog');
        this._bindEvents();
    }

    _bindEvents() {
        this.container.addEventListener('pointerdown', this.onPointerDown.bind(this));
        window.addEventListener('pointermove', this.onPointerMove.bind(this));
        window.addEventListener('pointerup', this.onPointerUp.bind(this));
        this.container.addEventListener('contextmenu', this.onContextMenu.bind(this));
        document.addEventListener('click', (e) => {
            if (!this.contextMenu.contains(e.target)) this.hideContextMenu();
            // Hide edge menu on outside click
            // FIX: Check edgeMenu.element.contains
            if (this.edgeMenu && this.edgeMenu.element && !this.edgeMenu.element.contains(e.target)) {
                // Also ensure the click wasn't on the edge itself which might re-trigger the menu
                const edgeObj = this.mindMap.intersectedObject(e.clientX, e.clientY);
                if (this.mindMap.selectedEdge !== edgeObj) {
                    this.mindMap.setSelectedEdge(null); // This implicitly hides the menu via the setter
                }
            }
        }, true); // Use capture phase
        this.contextMenu.addEventListener('click', this.onMenuClick.bind(this));
        $('#confirm-yes').addEventListener('click', this.onConfirmYes.bind(this));
        $('#confirm-no').addEventListener('click', this.onConfirmNo.bind(this));
        window.addEventListener('keydown', this.onKeyDown.bind(this));
        //this.container.addEventListener('wheel', this.onNodeWheel.bind(this), { passive: false });
    }

    targetInfo(event) {
        const e = document.elementFromPoint(event.clientX, event.clientY);
        const nodeElement = e?.closest('.node-html');
        const resizeHandle = e?.closest('.resize-handle');
        const nodeControls = e?.closest('.node-controls button'); // Any button in controls
        const contentEditable = e?.closest('.node-content[contenteditable="true"]');
        const interactiveElement = e?.closest('.node-content button, .node-content input, .node-content a');

        const node = nodeElement ? this.mindMap.getNodeById(nodeElement.dataset.nodeId) : null;
        // Check edge intersection only if not clicking on a node element or its controls
        const intersectedEdge = (!nodeElement) ? this.mindMap.intersectedObject(event.clientX, event.clientY) : null;

        return {
            element: e,
            nodeElement,
            resizeHandle,
            nodeControls,
            contentEditable,
            interactiveElement,
            node,
            intersectedEdge
        };
    }

    onPointerDown(e) {
        const {
            nodeElement,
            resizeHandle,
            nodeControls,
            contentEditable,
            interactiveElement,
            node,
            intersectedEdge
        } = this.targetInfo(e);
        const eb = e.button;
        this.isMouseDown = eb === 0; // Primary button
        this.isRightMouseDown = eb === 2;
        this.potentialClick = true;
        this.lastPointerPos = {x: e.clientX, y: e.clientY};

        if (eb === 2) { // Right click
            e.preventDefault();
            // Autozoom handled on pointerup/contextmenu
            return;
        }

        // --- Handle Node Quick Buttons / Resize First ---
        if (this.isMouseDown) {
            if (nodeControls && node) { // Clicked a button within node controls
                e.preventDefault();
                e.stopPropagation();
                const b = e.target.closest('button');
                let bClasses = b?.classList;
                if (bClasses?.contains('node-delete')) this.showConfirm(`Delete node "${node.id.substring(0, 10)}..."?`, () => this.mindMap.removeNode(node.id));
                else if (bClasses?.contains('node-content-zoom-in')) node.adjustContentScale(0.15);
                else if (bClasses?.contains('node-content-zoom-out')) node.adjustContentScale(-0.15);
                else if (bClasses?.contains('node-grow')) node.adjustNodeSize(1.25);
                else if (bClasses?.contains('node-shrink')) node.adjustNodeSize(0.8);
                this.hideContextMenu();
                return;
            }
            if (resizeHandle && node) {
                e.preventDefault();
                e.stopPropagation();
                this.resizedNode = node;
                this.resizedNode.startResize();
                this.resizeStartPos = {x: e.clientX, y: e.clientY};
                this.resizeStartSize = {...this.resizedNode.size};
                this.container.style.cursor = 'nwse-resize';
                this.hideContextMenu();
                return;
            }

            // --- Handle Node Drag / Selection ---
            // Allow drag only if clicking directly on node bg, not controls/content/interactive
            if (nodeElement && !nodeControls && !resizeHandle && !interactiveElement && !contentEditable) {
                e.preventDefault(); // Prevent text selection/browser drag
                this.draggedNode = node; // 'node' comes from _getTargetInfo
                // FIX: Check if node exists before calling startDrag
                if (this.draggedNode) {
                    this.draggedNode.startDrag();
                    const worldPos = this.mindMap.screen2world(e.clientX, e.clientY, this.draggedNode.position.z);
                    this.dragOffset = worldPos ? worldPos.sub(this.draggedNode.position) : new THREE.Vector3();
                    this.container.style.cursor = 'grabbing';
                    this.mindMap.setSelectedNode(node); // Select node on drag start
                } else {
                    console.warn("Attempted to drag non-existent node for element:", nodeElement);
                    this.draggedNode = null; // Ensure it's null if node lookup failed
                }
                this.hideContextMenu();
                return;
            }
        }
        // If clicking interactive or editable, stop propagation so background doesn't pan
        if (nodeElement && (interactiveElement || contentEditable)) {
            e.stopPropagation(); // Prevent pan
            this.mindMap.setSelectedNode(node); // Select node even when clicking inside
            this.hideContextMenu();
            return;
        }

        // --- Handle Edge Selection ---
        if (intersectedEdge && !node) { // Prioritize nodes over edges if overlapping
            e.preventDefault();
            this.mindMap.setSelectedEdge(intersectedEdge);
            this.hideContextMenu();
            return; // Don't pan if edge selected
        }

        // --- Handle Background Interaction (Panning or Deselection) ---
        if (!nodeElement && !intersectedEdge) { // Clicked on background
            this.hideContextMenu();
            if (this.mindMap.selectedNode || this.mindMap.selectedEdge) {
                // Deselect on background click
                this.mindMap.setSelectedNode(null);
                this.mindMap.setSelectedEdge(null);
            } else {
                // Allow CameraController to handle panning (it checks isMouseDown)
                this.mindMap.cameraController?.startPan(e);
            }
        }
    }

    onPointerMove(e) {
        const dx = e.clientX - this.lastPointerPos.x;
        const dy = e.clientY - this.lastPointerPos.y;
        if (dx !== 0 || dy !== 0) this.potentialClick = false;
        this.lastPointerPos = {x: e.clientX, y: e.clientY};

        // --- Handle Resizing ---
        if (this.resizedNode) {
            e.preventDefault();
            const newWidth = this.resizeStartSize.width + (e.clientX - this.resizeStartPos.x);
            const newHeight = this.resizeStartSize.height + (e.clientY - this.resizeStartPos.y);
            this.resizedNode.resize(newWidth, newHeight);
            return; // Prevent other move actions
        }

        // --- Handle Dragging ---
        if (this.draggedNode) {
            e.preventDefault();
            const worldPos = this.mindMap.screen2world(e.clientX, e.clientY, this.draggedNode.position.z);
            if (worldPos) this.draggedNode.drag(worldPos.sub(this.dragOffset));
            return; // Prevent other move actions
        }

        // --- Handle Linking Line ---
        if (this.mindMap.isLinking) {
            e.preventDefault(); // Prevent panning while linking
            this._updateTempLinkLine(e.clientX, e.clientY);
            const {node} = this.targetInfo(e);
            $$('.node-html.linking-target').forEach(el => el.classList.remove('linking-target'));
            if (node && node !== this.mindMap.linkSourceNode) {
                node.htmlElement?.classList.add('linking-target');
            }
            return;
        }

        // --- Handle Panning (delegated to CameraController) ---
        this.mindMap.cameraController?.pan(e);

        // --- Handle Edge Highlighting on Hover (if nothing else is active) ---
        if (!this.isMouseDown && !this.resizedNode && !this.draggedNode && !this.mindMap.isLinking) {
            const {intersectedEdge} = this.targetInfo(e);
            if (this.hoveredEdge !== intersectedEdge) {
                if (this.hoveredEdge && this.hoveredEdge !== this.mindMap.selectedEdge) {
                    this.hoveredEdge.setHighlight(false);
                }
                this.hoveredEdge = intersectedEdge;
                if (this.hoveredEdge && this.hoveredEdge !== this.mindMap.selectedEdge) {
                    this.hoveredEdge.setHighlight(true);
                }
            }
        }
    }

    onPointerUp(e) {
        this.container.style.cursor = this.mindMap.isLinking ? 'crosshair' : 'grab';

        if (this.resizedNode) {
            this.resizedNode.endResize();
            this.resizedNode = null;
        } else if (this.draggedNode) {
            this.draggedNode.endDrag();
            this.draggedNode = null;
        } else if (this.mindMap.isLinking) {
            this._endLinking(e);
        } else if (e.button === 1) { // Middle button
            const {node} = this.targetInfo(e);
            if (node) {
                this.mindMap.autoZoom(node);
                e.preventDefault();
            }
        } else if (e.button === 2 && this.isRightMouseDown && this.potentialClick) { // Right-click finish
            // Context menu should appear via 'contextmenu' event
        } else if (e.button === 0 && this.potentialClick) { // Primary click finish
            const {
                node,
                intersectedEdge,
                contentEditable,
                interactiveElement,
                nodeControls,
                resizeHandle
            } = this.targetInfo(e);
            // If click was on a control, it was handled in pointerdown, do nothing here
            if (!nodeControls && !resizeHandle) {
                if (node && !contentEditable && !interactiveElement) {
                    // Simple click on node (already selected in pointerdown)
                } else if (intersectedEdge && !node) {
                    // Simple click on edge (already selected in pointerdown)
                } else if (!node && !intersectedEdge && !this.mindMap.cameraController?.isPanning) {
                    // Background click - deselect handled by document click listener
                }
            }
        }

        // End panning
        this.mindMap.cameraController?.endPan();

        this.isMouseDown = false;
        this.isRightMouseDown = false;
        this.potentialClick = false; // Reset potential click flag
        $$('.node-html.linking-target').forEach(el => el.classList.remove('linking-target'));
    }

    onContextMenu(e) {
        e.preventDefault();
        this.hideContextMenu(); // Hide previous first
        const {node, intersectedEdge} = this.targetInfo(e);

        let items = [];
        if (node) {
            if (this.mindMap.selectedNode !== node) this.mindMap.setSelectedNode(node); // Select node on right click if not already selected
            items = [
                {
                    label: "Edit Content 📝",
                    action: "edit-node",
                    nodeId: node.id,
                    disabled: !(node instanceof NoteNode)
                },
                {label: "Start Link ✨", action: "start-link", nodeId: node.id},
                {label: "Auto Zoom / Back 🖱️", action: "autozoom-node", nodeId: node.id},
                {type: 'separator'},
                {label: "Delete Node 🗑️", action: "delete-node", nodeId: node.id},
            ];
        } else if (intersectedEdge) {
            if (this.mindMap.selectedEdge !== intersectedEdge) this.mindMap.setSelectedEdge(intersectedEdge); // Select edge on right click
            items = [
                {label: "Edit Edge Style...", action: "edit-edge", edgeId: intersectedEdge.id}, // Placeholder
                {label: "Reverse Edge Direction", action: "reverse-edge", edgeId: intersectedEdge.id},
                {type: 'separator'},
                {label: "Delete Edge 🗑️", action: "delete-edge", edgeId: intersectedEdge.id},
            ];
        } else {
            // Deselect if right-clicking background
            this.mindMap.setSelectedNode(null);
            this.mindMap.setSelectedEdge(null);
            const worldPos = this.mindMap.screen2world(e.clientX, e.clientY, 0);
            items = [
                {
                    label: "Create Note Here ➕",
                    action: "create-note",
                    position: worldPos ? JSON.stringify(worldPos) : null
                },
                {label: "Center View 🧭", action: "center-view"},
                {label: "Reset Zoom & Pan", action: "reset-view"},
            ];
        }

        if (items.length > 0) this.showContextMenu(e.clientX, e.clientY, items);
    }

    showContextMenu(x, y, items) {
        this.contextMenu.innerHTML = '';
        const ul = document.createElement('ul');
        items.forEach(item => {
            if (item.type === 'separator') {
                const li = document.createElement('li');
                li.className = 'separator';
                ul.appendChild(li);
                return;
            }
            if (item.disabled) return;
            const li = document.createElement('li');
            li.textContent = item.label;
            Object.entries(item).forEach(([key, value]) => {
                if (value !== undefined && value !== null && key !== 'type' && key !== 'label') li.dataset[key] = value;
            });
            ul.appendChild(li);
        });
        this.contextMenu.appendChild(ul);

        const menuWidth = this.contextMenu.offsetWidth;
        const menuHeight = this.contextMenu.offsetHeight;
        let finalX = x + 5;
        let finalY = y + 5;
        if (finalX + menuWidth > window.innerWidth) finalX = x - menuWidth - 5;
        if (finalY + menuHeight > window.innerHeight) finalY = y - menuHeight - 5;
        finalX = Math.max(5, finalX);
        finalY = Math.max(5, finalY);

        this.contextMenu.style.left = `${finalX}px`;
        this.contextMenu.style.top = `${finalY}px`;
        this.contextMenu.style.display = 'block';
    }

    hideContextMenu = () => {
        if (this.contextMenu) this.contextMenu.style.display = 'none';
    }

    onMenuClick(event) {
        const li = event.target.closest('li');
        if (!li || !li.dataset.action) return;
        const data = li.dataset;
        const action = data.action;
        this.hideContextMenu();

        switch (action) {
            case 'edit-node': {
                const node = this.mindMap.getNodeById(data.nodeId);
                const contentDiv = node?.htmlElement?.querySelector('.node-content');
                if (contentDiv?.contentEditable === "true") contentDiv.focus();
                break;
            }
            case 'delete-node':
                this.showConfirm(`Delete node "${data.nodeId.substring(0, 10)}..."?`, () => this.mindMap.removeNode(data.nodeId));
                break;
            case 'delete-edge':
                this.showConfirm(`Delete edge "${data.edgeId.substring(0, 10)}..."?`, () => this.mindMap.removeEdge(data.edgeId));
                break;
            case 'autozoom-node': {
                const node = this.mindMap.getNodeById(data.nodeId);
                if (node) this.mindMap.autoZoom(node);
                break;
            }
            case 'create-note': {
                if (!data.position) break;
                const pos = JSON.parse(data.position);
                const newNode = this.mindMap.addNode(new NoteNode(null, pos, {content: 'New Note ✨'}));
                this.mindMap.layoutEngine?.kick();
                setTimeout(() => {
                    this.mindMap.focusOnNode(newNode, 0.6, true); // Push history for new node focus
                    newNode.htmlElement?.querySelector('.node-content')?.focus();
                    this.mindMap.setSelectedNode(newNode); // Select the new node
                }, 100);
                break;
            }
            case 'center-view':
                this.mindMap.centerView();
                break;
            case 'reset-view':
                this.mindMap.cameraController?.resetView();
                break;
            case 'start-link': {
                const sourceNode = this.mindMap.getNodeById(data.nodeId);
                if (sourceNode) {
                    this.mindMap.isLinking = true;
                    this.mindMap.linkSourceNode = sourceNode;
                    this.container.style.cursor = 'crosshair';
                    this._startTempLinkLine(sourceNode);
                }
                break;
            }
            case 'reverse-edge': {
                const edge = this.mindMap.getEdgeById(data.edgeId);
                if (edge) {
                    const oldSource = edge.source;
                    edge.source = edge.target;
                    edge.target = oldSource;
                    edge.update(); // Update visual immediately
                    this.mindMap.layoutEngine?.kick(); // Nudge layout
                }
                break;
            }
            case 'edit-edge':
                console.warn("Edit Edge Style action not fully implemented.");
                // Ensure menu is shown if not already (might be hidden by context menu closing)
                this.showEdgeMenu(this.mindMap.getEdgeById(data.edgeId));
                break;
        }
    }

    showConfirm(message, onConfirm) {
        $('#confirm-message').textContent = message;
        this.confirmCallback = onConfirm;
        this.confirmDialog.style.display = 'block';
    }

    hideConfirm = () => {
        this.confirmDialog.style.display = 'none';
        this.confirmCallback = null;
    }
    onConfirmYes = () => {
        this.confirmCallback?.();
        this.hideConfirm();
    }
    onConfirmNo = () => {
        this.hideConfirm();
    }

    _startTempLinkLine(sourceNode) {
        this._removeTempLinkLine();
        const material = new THREE.LineDashedMaterial({
            color: 0xffaa00,
            linewidth: 2,
            dashSize: 8,
            gapSize: 4,
            transparent: true,
            opacity: 0.9,
            depthTest: false
        });
        const points = [sourceNode.position.clone(), sourceNode.position.clone()];
        const geometry = new THREE.BufferGeometry().setFromPoints(points);
        this.mindMap.tempLinkLine = new THREE.Line(geometry, material);
        this.mindMap.tempLinkLine.computeLineDistances();
        this.mindMap.tempLinkLine.renderOrder = 1;
        this.mindMap.scene.add(this.mindMap.tempLinkLine);
    }

    _updateTempLinkLine(screenX, screenY) {
        if (!this.mindMap.tempLinkLine || !this.mindMap.linkSourceNode) return;
        const targetPos = this.mindMap.screen2world(screenX, screenY, this.mindMap.linkSourceNode.position.z);
        if (targetPos) {
            const positions = this.mindMap.tempLinkLine.geometry.attributes.position;
            positions.setXYZ(1, targetPos.x, targetPos.y, targetPos.z);
            positions.needsUpdate = true;
            this.mindMap.tempLinkLine.geometry.computeBoundingSphere();
            this.mindMap.tempLinkLine.computeLineDistances();
        }
    }

    _removeTempLinkLine() {
        if (this.mindMap.tempLinkLine) {
            this.mindMap.tempLinkLine.geometry?.dispose();
            this.mindMap.tempLinkLine.material?.dispose();
            this.mindMap.scene.remove(this.mindMap.tempLinkLine);
            this.mindMap.tempLinkLine = null;
        }
    }

    _endLinking(event) {
        this._removeTempLinkLine();
        const {node: targetNode} = this.targetInfo(event);
        if (targetNode && targetNode !== this.mindMap.linkSourceNode) {
            this.mindMap.addEdge(this.mindMap.linkSourceNode, targetNode);
        }
        this.cancelLinking();
    }

    cancelLinking() {
        this._removeTempLinkLine();
        this.mindMap.isLinking = false;
        this.mindMap.linkSourceNode = null;
        this.container.style.cursor = 'grab';
        $$('.node-html.linking-target').forEach(el => el.classList.remove('linking-target'));
    }

    onKeyDown(event) {
        if (document.activeElement && ['INPUT', 'TEXTAREA', 'DIV'].includes(document.activeElement.tagName) && document.activeElement.isContentEditable) {
            if (event.key === 'Escape' && this.mindMap.isLinking) {
                event.preventDefault();
                this.cancelLinking();
            }
            return; // Don't process shortcuts if editing text
        }

        const selectedNode = this.mindMap.selectedNode;
        const selectedEdge = this.mindMap.selectedEdge;

        switch (event.key) {
            case 'Delete':
            case 'Backspace':
                if (selectedNode) {
                    this.removeTry(selectedNode, event);
                } else if (selectedEdge) {
                    this.removeTry(selectedEdge, event);
                }
                break;
            case 'Escape':
                event.preventDefault();
                if (this.mindMap.isLinking) {
                    this.cancelLinking();
                } else if (this.contextMenu.style.display === 'block') {
                    this.hideContextMenu();
                } else if (this.confirmDialog.style.display === 'block') {
                    this.hideConfirm();
                } else if (this.edgeMenu) {
                    this.mindMap.setSelectedEdge(null); // Deselect edge (hides menu)
                } else if (selectedNode || selectedEdge) {
                    this.mindMap.setSelectedNode(null);
                    this.mindMap.setSelectedEdge(null);
                }
                break;
            case 'Enter':
                if (selectedNode && selectedNode instanceof NoteNode) {
                    event.preventDefault();
                    selectedNode.htmlElement?.querySelector('.node-content')?.focus();
                }
                break;
            case '+':
            case '=': // Often shares key with +
                if (selectedNode && event.ctrlKey) { // Ctrl + + : Grow Node
                    event.preventDefault();
                    selectedNode.adjustNodeSize(1.25);
                } else if (selectedNode) { // Just + : Zoom Content
                    event.preventDefault();
                    selectedNode.adjustContentScale(0.15);
                }
                break;
            case '-':
            case '_': // Often shares key with -
                if (selectedNode && event.ctrlKey) { // Ctrl + - : Shrink Node
                    event.preventDefault();
                    selectedNode.adjustNodeSize(0.8);
                } else if (selectedNode) { // Just - : Zoom Content
                    event.preventDefault();
                    selectedNode.adjustContentScale(-0.15);
                }
                break;
            case ' ': // Spacebar - Recenter on selection or overall view
                if (selectedNode) {
                    event.preventDefault();
                    this.mindMap.focusOnNode(selectedNode, 0.5, true);
                } else if (selectedEdge) {
                    event.preventDefault();
                    // Focus on midpoint of edge?
                    const midPoint = new THREE.Vector3().lerpVectors(selectedEdge.source.position, selectedEdge.target.position, 0.5);
                    const dist = selectedEdge.source.position.distanceTo(selectedEdge.target.position);
                    this.mindMap.cameraController?.pushState();
                    this.mindMap.cameraController?.moveTo(midPoint.x, midPoint.y, midPoint.z + dist * 0.6 + 100, 0.5, midPoint);
                } else {
                    event.preventDefault();
                    this.mindMap.centerView();
                }
                break;
        }
    }

    removeTry(selected, event) {
        event.preventDefault();
        this.showConfirm(`Delete node "${selected.id.substring(0, 10)}..."?`, () => this.mindMap.removeNode(selected.id));
    }

    onNodeWheel(event) {
        const {node, contentEditable} = this.targetInfo(event);
        // Allow wheel scroll inside contentEditable OR if node controls are hovered
        const controlsHovered = event.target.closest('.node-controls');
        if (node && !contentEditable && !controlsHovered) {
            event.preventDefault();
            event.stopPropagation();
            const delta = -event.deltaY * 0.001; // Adjust sensitivity
            node.adjustContentScale(delta);
        }
        // Allow default wheel behavior (zoom/pan) if not over a node's main area
    }

    showEdgeMenu(edge) {
        if (!edge || this.edgeMenu) return; // Don't show if already visible or no edge

        const menu = document.createElement('div');
        menu.className = 'edge-menu-frame';
        menu.dataset.edgeId = edge.id;
        menu.innerHTML = `
              <button title="Color (NYI)" data-action="color">🎨</button>
              <button title="Thickness (NYI)" data-action="thickness">➖</button>
              <button title="Style (NYI)" data-action="style">〰️</button>
              <button title="Constraint (NYI)" data-action="constraint">🔗</button>
              <button title="Delete Edge" class="delete" data-action="delete">×</button>
          `;

        menu.addEventListener('click', (e) => {
            const button = e.target.closest('button');
            if (!button) return;
            const action = button.dataset.action;
            e.stopPropagation(); // Prevent click from bubbling to document/deselecting

            switch (action) {
                case 'delete':
                    this.showConfirm(`Delete edge "${edge.id.substring(0, 10)}..."?`, () => this.mindMap.removeEdge(edge.id));
                    // No need to hide menu here, removeEdge->setSelectedEdge(null) handles it
                    break;
                default:
                    console.warn(`Edge menu action '${action}' not implemented.`);
            }
        });

        this.edgeMenu = new CSS3DObject(menu);
        this.mindMap.cssScene.add(this.edgeMenu);
        this.updateEdgeMenuPosition(); // Initial position
    }

    hideEdgeMenu() {
        if (this.edgeMenu) {
            this.edgeMenu.element?.remove(); // Clean up HTML element
            this.edgeMenu.parent?.remove(this.edgeMenu);
            this.edgeMenu = null;
        }
    }

    updateEdgeMenuPosition() {
        if (!this.edgeMenu || !this.mindMap.selectedEdge) return;
        const edge = this.mindMap.selectedEdge;
        const midPoint = new THREE.Vector3().lerpVectors(edge.source.position, edge.target.position, 0.5);
        this.edgeMenu.position.copy(midPoint);
        // Optional: Make menu face camera
        // this.edgeMenu.rotation.copy(this.mindMap.camera.rotation);
    }
}

class Camera {
    isPanning = false;
    panStart = new THREE.Vector2();
    targetPosition = new THREE.Vector3();
    targetLookAt = new THREE.Vector3();
    currentLookAt = new THREE.Vector3();
    zoomSpeed = 0.0015;
    panSpeed = 0.8;
    minZoom = 20;
    maxZoom = 15000;
    dampingFactor = 0.12;
    animationFrameId = null;
    viewHistory = []; // Stack for autozoom back/forward
    maxHistory = 20;
    currentTargetNodeId = null; // Track which node is targeted by autozoom
    initialState = null; // Store initial state after first positioning

    constructor(mindMap) {
        this.camera = mindMap._cam;
        this.domElement = mindMap.container;
        // Initial state set after first centerView/focus
        this.targetPosition.copy(this.camera.position);
        this.targetLookAt.copy(new THREE.Vector3(0, 0, 0)); // Assume looking at origin initially
        this.currentLookAt.copy(this.targetLookAt);

        this._bindEvents();
        this.update();
    }

    _bindEvents() {
        // Wheel and pointer events handled by UIManager, which calls camera methods
    }

    setInitialState() {
        if (!this.initialState) {
            this.initialState = {
                position: this.camera.position.clone(),
                lookAt: this.currentLookAt.clone() // Use current smoothed lookAt
            };
        }
    }

    // Called by UIManager on background pointer down
    startPan(event) {
        if (event.button !== 0 || this.isPanning) return; // Only primary button pan, prevent re-entry
        this.isPanning = true;
        this.panStart.set(event.clientX, event.clientY);
        this.domElement.classList.add('panning');
        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);
        this.currentTargetNodeId = null; // User interaction cancels autozoom target
    }

    // Called by UIManager on pointer move if panning
    pan(event) {
        if (!this.isPanning) return;
        const deltaX = event.clientX - this.panStart.x;
        const deltaY = event.clientY - this.panStart.y;

        const cameraDist = this.camera.position.distanceTo(this.currentLookAt);
        const vFOV = this.camera.fov * DEG2RAD;
        // Use clientHeight which is more reliable than domElement.clientHeight if domElement is canvas
        const viewHeight = this.domElement.clientHeight || window.innerHeight;
        const height = 2 * Math.tan(vFOV / 2) * Math.max(1, cameraDist);

        const panX = -(deltaX / viewHeight) * height * this.panSpeed;
        const panY = (deltaY / viewHeight) * height * this.panSpeed;

        const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
        const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);
        const panOffset = right.multiplyScalar(panX).add(up.multiplyScalar(panY));

        this.targetPosition.add(panOffset);
        this.targetLookAt.add(panOffset);
        this.panStart.set(event.clientX, event.clientY);
    }

    // Called by UIManager on pointer up if panning
    endPan() {
        if (this.isPanning) {
            this.isPanning = false;
            this.domElement.classList.remove('panning');
        }
    }

    // Called by UIManager on wheel event if not handled by node
    zoom(event) {
        event.preventDefault();
        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);
        this.currentTargetNodeId = null; // User interaction cancels autozoom target

        const delta = -event.deltaY * this.zoomSpeed;
        const currentDist = this.targetPosition.distanceTo(this.targetLookAt);
        let newDist = currentDist * Math.pow(0.95, delta * 12); // Adjusted multiplier
        newDist = clamp(newDist, this.minZoom, this.maxZoom);

        const zoomFactor = (newDist - currentDist); // Amount to move along direction

        // Zoom towards mouse pointer projected onto the lookAt plane
        const mouseWorldPos = this._getLookAtPlaneIntersection(event.clientX, event.clientY);
        const direction = new THREE.Vector3();

        if (mouseWorldPos) {
            direction.copy(mouseWorldPos).sub(this.targetPosition).normalize();
        } else {
            // Fallback: Zoom along camera view direction
            this.camera.getWorldDirection(direction);
        }
        this.targetPosition.addScaledVector(direction, zoomFactor);
    }

    moveTo(x, y, z, duration = 0.7, lookAtTarget = null) {
        this.setInitialState(); // Ensure initial state is set before first move
        const targetPos = new THREE.Vector3(x, y, z);
        const targetLook = lookAtTarget ? lookAtTarget.clone() : new THREE.Vector3(x, y, 0); // Default lookAt XY plane

        gsap.killTweensOf(this.targetPosition); // Ensure smooth transition
        gsap.killTweensOf(this.targetLookAt);

        gsap.to(this.targetPosition, {
            x: targetPos.x,
            y: targetPos.y,
            z: targetPos.z,
            duration: duration,
            ease: "power3.out",
            overwrite: true
        });
        gsap.to(this.targetLookAt, {
            x: targetLook.x,
            y: targetLook.y,
            z: targetLook.z,
            duration: duration,
            ease: "power3.out",
            overwrite: true
        });

    }

    resetView(duration = 0.7) {
        if (this.initialState) {
            this.moveTo(this.initialState.position.x, this.initialState.position.y, this.initialState.position.z, duration, this.initialState.lookAt);
        } else {
            // Fallback if initial state wasn't set (shouldn't happen ideally)
            this.moveTo(0, 0, 700, duration, new THREE.Vector3(0, 0, 0));
        }
        this.viewHistory = []; // Clear history on reset
        this.currentTargetNodeId = null;
    }

    pushState() {
        if (this.viewHistory.length >= this.maxHistory) {
            this.viewHistory.shift(); // Remove oldest state
        }
        // Push the *current* target state, not the camera's potentially lagging position
        this.viewHistory.push({
            position: this.targetPosition.clone(),
            lookAt: this.targetLookAt.clone(),
            targetNodeId: this.currentTargetNodeId
        });
    }

    popState(duration = 0.6) {
        if (this.viewHistory.length > 0) {
            const prevState = this.viewHistory.pop();
            this.moveTo(prevState.position.x, prevState.position.y, prevState.position.z, duration, prevState.lookAt);
            this.currentTargetNodeId = prevState.targetNodeId; // Restore previous target
        } else {
            this.resetView(duration); // Go to initial state if history is empty
        }
    }

    getCurrentTargetNodeId = () => this.currentTargetNodeId;
    setCurrentTargetNodeId = (nodeId) => {
        this.currentTargetNodeId = nodeId;
    };

    update = () => {
        const deltaPos = this.targetPosition.distanceTo(this.camera.position);
        const deltaLookAt = this.targetLookAt.distanceTo(this.currentLookAt);

        // Only lerp if moving significantly or panning
        if (deltaPos > 0.01 || deltaLookAt > 0.01 || this.isPanning) {
            this.camera.position.lerp(this.targetPosition, this.dampingFactor);
            this.currentLookAt.lerp(this.targetLookAt, this.dampingFactor);
            this.camera.lookAt(this.currentLookAt);
        } else if (!gsap.isTweening(this.targetPosition) && !gsap.isTweening(this.targetLookAt)) {
            // Snap to final position only if close enough AND no animation is running
            if (deltaPos > 0 || deltaLookAt > 0) { // Avoid unnecessary updates if already there
                this.camera.position.copy(this.targetPosition);
                this.currentLookAt.copy(this.targetLookAt);
                this.camera.lookAt(this.currentLookAt);
            }
        }
        this.animationFrameId = requestAnimationFrame(this.update);
    }

    dispose() {
        if (this.animationFrameId) cancelAnimationFrame(this.animationFrameId);
        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);
        // Remove event listeners if they were added directly here
    }

    _getLookAtPlaneIntersection(screenX, screenY) {
        const vec = new THREE.Vector3((screenX / window.innerWidth) * 2 - 1, -(screenY / window.innerHeight) * 2 + 1, 0.5);
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(vec, this.camera);
        const camDir = new THREE.Vector3();
        this.camera.getWorldDirection(camDir);
        // Use targetLookAt for the plane's reference point
        const plane = new THREE.Plane().setFromNormalAndCoplanarPoint(camDir.negate(), this.targetLookAt);
        const intersectPoint = new THREE.Vector3();
        return raycaster.ray.intersectPlane(plane, intersectPoint) ? intersectPoint : null;
    }
}

class ForceLayout {
    nodes = [];
    edges = [];
    velocities = new Map();
    fixedNodes = new Set();
    isRunning = false;
    animationFrameId = null;
    energy = Infinity;
    lastKickTime = 0;
    autoStopTimeout = null;

    settings = {
        repulsion: 3000,
        attraction: 0.001,
        idealEdgeLength: 200,
        centerStrength: 0.0005,
        damping: 0.92, // Slightly less damping for more subtle movement
        minEnergyThreshold: 0.1, // Stop when energy is low
        gravityCenter: new THREE.Vector3(0, 0, 0),
        zSpreadFactor: 0.15, // Slightly more Z influence
        autoStopDelay: 4000 // Stop simulation after 4s of inactivity
    };

    constructor(mindMap) {
        this.mindMap = mindMap;
    }

    addNode(node) {
        if (!this.velocities.has(node.id)) {
            this.nodes.push(node);
            this.velocities.set(node.id, new THREE.Vector3());
            this.kick();
        }
    }

    removeNode(node) {
        this.nodes = this.nodes.filter(n => n !== node);
        this.velocities.delete(node.id);
        this.fixedNodes.delete(node);
        this.kick();
    }

    addEdge(edge) {
        if (!this.edges.includes(edge)) {
            this.edges.push(edge);
            this.kick();
        }
    }

    removeEdge(edge) {
        this.edges = this.edges.filter(e => e !== edge);
        this.kick();
    }

    fixNode(node) {
        this.fixedNodes.add(node);
        this.velocities.get(node.id)?.set(0, 0, 0);
    }

    releaseNode(node) {
        this.fixedNodes.delete(node);
    }

    runOnce(steps = 100) {
        for (let i = 0; i < steps; i++) {
            if (this._calculateStep() < this.settings.minEnergyThreshold) break;
        }
    }

    start() {
        if (this.isRunning) return;
        this.isRunning = true;
        const loop = () => {
            if (!this.isRunning) return;
            this.energy = this._calculateStep();
            // Check energy and time since last kick for auto-stop
            if (this.energy < this.settings.minEnergyThreshold && Date.now() - this.lastKickTime > this.settings.autoStopDelay) {
                this.stop();
            } else {
                this.animationFrameId = requestAnimationFrame(loop);
            }
        };
        this.animationFrameId = requestAnimationFrame(loop);
    }

    stop() {
        this.isRunning = false;
        if (this.animationFrameId) cancelAnimationFrame(this.animationFrameId);
        this.animationFrameId = null;
        clearTimeout(this.autoStopTimeout);
        this.autoStopTimeout = null;
        // console.log("Layout simulation stopped.");
    }

    kick(intensity = 1) {
        this.lastKickTime = Date.now();
        this.nodes.forEach(node => {
            if (!this.fixedNodes.has(node)) {
                this.velocities.get(node.id)?.add(
                    new THREE.Vector3(Math.random() - 0.5, Math.random() - 0.5, (Math.random() - 0.5) * this.settings.zSpreadFactor)
                        .normalize().multiplyScalar(intensity * 5 + Math.random() * 5) // Add randomness
                );
            }
        });
        if (!this.isRunning) this.start();

        // Reset auto-stop timer on kick
        clearTimeout(this.autoStopTimeout);
        this.autoStopTimeout = setTimeout(() => {
            // Check energy again before stopping, might have been kicked again
            if (this.isRunning && this.energy < this.settings.minEnergyThreshold) {
                this.stop();
            }
        }, this.settings.autoStopDelay);
    }

    _calculateStep() {
        if (this.nodes.length < 2) return 0;
        let totalEnergy = 0;
        const forces = new Map(this.nodes.map(node => [node.id, new THREE.Vector3()]));
        const {
            repulsion,
            attraction,
            idealEdgeLength,
            centerStrength,
            gravityCenter,
            zSpreadFactor
        } = this.settings;

        // Repulsion (Node size aware)
        for (let i = 0; i < this.nodes.length; i++) {
            const nodeA = this.nodes[i];
            for (let j = i + 1; j < this.nodes.length; j++) {
                const nodeB = this.nodes[j];
                const delta = nodeB.position.clone().sub(nodeA.position);
                let distSq = delta.lengthSq();
                if (distSq < 1) {
                    distSq = 1;
                    delta.set(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5).normalize();
                }
                const dist = Math.sqrt(distSq);

                // Consider nodes as approximate circles/spheres for repulsion distance
                const radiusA = Math.sqrt(nodeA.size.width * nodeA.size.height) / 2;
                const radiusB = Math.sqrt(nodeB.size.width * nodeB.size.height) / 2;
                const combinedRadius = (radiusA + radiusB) * 1.1; // Add 10% buffer
                const overlap = combinedRadius - dist;

                let forceMag = -repulsion / distSq; // Basic repulsion

                if (overlap > 0) {
                    // Stronger repulsion if overlapping, proportional to overlap squared for faster separation
                    forceMag -= (repulsion * Math.pow(overlap, 2) * 0.01) / dist;
                }

                const forceVec = delta.normalize().multiplyScalar(forceMag);
                forceVec.z *= zSpreadFactor;

                forces.get(nodeA.id).add(forceVec);
                forces.get(nodeB.id).sub(forceVec);
            }
        }

        // Attraction (Edges)
        this.edges.forEach(edge => {
            const {source, target} = edge;
            if (!source || !target) return; // Skip if edge is invalid
            const delta = target.position.clone().sub(source.position);
            const dist = delta.length() + 0.1; // Add epsilon to avoid division by zero
            const effectiveIdealLength = idealEdgeLength * (edge.data.constraint === 'tight' ? 0.7 : (edge.data.constraint === 'loose' ? 1.3 : 1.0));
            const displacement = dist - effectiveIdealLength;
            const forceMag = attraction * displacement;
            const forceVec = delta.normalize().multiplyScalar(forceMag);
            forceVec.z *= zSpreadFactor;

            if (!this.fixedNodes.has(source)) forces.get(source.id)?.add(forceVec);
            if (!this.fixedNodes.has(target)) forces.get(target.id)?.sub(forceVec);
        });

        // Center Gravity
        if (centerStrength > 0) {
            this.nodes.forEach(node => {
                const forceVec = gravityCenter.clone().sub(node.position).multiplyScalar(centerStrength);
                forceVec.z *= zSpreadFactor * 0.5;
                forces.get(node.id)?.add(forceVec);
            });
        }

        // Apply forces
        this.nodes.forEach(node => {
            if (this.fixedNodes.has(node)) return;
            const force = forces.get(node.id);
            const velocity = this.velocities.get(node.id);
            if (!force || !velocity) return; // Skip if data is missing

            velocity.add(force).multiplyScalar(this.settings.damping);

            // Limit velocity to prevent explosion
            const speed = velocity.length();
            if (speed > 50) velocity.multiplyScalar(50 / speed);

            node.position.add(velocity);
            totalEnergy += velocity.lengthSq();
        });

        return totalEnergy;
    }
}

// --- Initialization ---
function init() {
    const container = $('#mindmap-container');
    if (!container) throw new Error("Mind map container not found!");

    const m = new MindMap(container);
    const layout = new ForceLayout(m);
    m.layout = layout;

    m.ui = new UIManager(m); // Initialize UI Manager AFTER core components



    layout.runOnce(200); // Initial layout settling
    layout.start(); // Keep layout subtly active

    exampleMindMap(m);

    m.animate();

    window.mindMap = m; // Expose mindMap for debugging
}

function exampleMindMap(mindMap) {
    const colors = ['#2a2a50', '#2a402a', '#402a2a', '#40402a', '#2a4040', '#402a40'];
    let colorIndex = 0;
    const nextColor = () => colors[colorIndex++ % colors.length];

    // Core Node
    const n1 = mindMap.addNode(new NoteNode(null, {x: 0, y: 0, z: 0}, {
        content: "<h1>🚀 NeuroWeaver 🧠</h1><p>Enhanced Mind Map Demo</p>",
        width: 300, height: 110, backgroundColor: nextColor()
    }));

    // Features Branch
    const n_features = mindMap.addNode(new NoteNode(null, {x: 350, y: 100, z: 20}, {
        content: "<h2>Features ✨</h2><ul><li>Autozoom (RMB / Menu)</li><li>Node Quick Actions (Hover)</li><li>Edge Selection/Menu</li><li>Force Layout</li><li>Interactive Nodes</li></ul>",
        width: 240, height: 180, backgroundColor: nextColor()
    }));
    mindMap.addEdge(n1, n_features);

    const n_autozoom = mindMap.addNode(new NoteNode(null, {x: 600, y: 150, z: 30}, {
        content: "<h3>Autozoom Detail</h3><p>Right-click a node to zoom. Click again or use menu to go back. History stack enabled.</p>",
        width: 200, height: 120, backgroundColor: colors[1] // Reuse color
    }));
    mindMap.addEdge(n_features, n_autozoom);

    // Tech Branch
    const n_tech = mindMap.addNode(new NoteNode(null, {x: -350, y: 100, z: -10}, {
        content: "<h2>Technology 💻</h2><p><code>HTML</code> <code>CSS</code> <code>JS (ESM)</code></p><p><b>Three.js</b> + <b>CSS3DRenderer</b></p><p><b>GSAP</b> for animation</p>",
        width: 250, height: 140, backgroundColor: nextColor()
    }));
    mindMap.addEdge(n1, n_tech);

    // Style Branch
    const n_style = mindMap.addNode(new NoteNode(null, {x: 0, y: -250, z: 0}, {
        content: "<h2>Style 🎨</h2><p>✨ Minimal Dark Mode</p><p>🎨 Varied Node Colors</p><p>🕸️ Subtle Dot Grid</p><p>🔧 Simple CSS Vars</p>",
        width: 220, height: 140, backgroundColor: nextColor()
    }));
    mindMap.addEdge(n1, n_style);

    // Interactive Node Example
    const interactiveNodeId = generateId('interactive');
    const n_interactive = mindMap.addNode(new NoteNode(interactiveNodeId, {x: 350, y: -150, z: -30}, {
        content: `<h2>Interactive HTML</h2>
                    <p>Slider value: <span id="slider-val-${interactiveNodeId}">50</span></p>
                    <input type="range" min="0" max="100" value="50" style="width: 90%; pointer-events: auto;"
                           oninput="document.getElementById('slider-val-${interactiveNodeId}').textContent = this.value">
                    <button onclick="alert('Button inside node ${interactiveNodeId} clicked!')" style="pointer-events: auto;">Click Me</button>`,
        width: 230, height: 160, backgroundColor: nextColor()
    }));
    mindMap.addEdge(n_features, n_interactive);
    mindMap.addEdge(n_style, n_interactive); // Cross link

    // Hierarchical / Fractal Example
    const n_fractal_root = mindMap.addNode(new NoteNode(null, {x: -400, y: -200, z: 50}, {
        content: "<h3>Hierarchy</h3>", width: 120, height: 50, backgroundColor: colors[0]
    }));
    mindMap.addEdge(n_tech, n_fractal_root);

    for (let i = 0; i < 3; i++) {
        const angle = (i / 3) * Math.PI * 2;
        const np = n_fractal_root.position;
        const n_level1 = mindMap.addNode(new NoteNode(null, {
            x: np.x + Math.cos(angle) * 150,
            y: np.y + Math.sin(angle) * 150,
            z: np.z + (Math.random() - 0.5) * 40
        }, {content: `L1-${i + 1}`, width: 80, height: 40, backgroundColor: colors[1]}));
        mindMap.addEdge(n_fractal_root, n_level1);

        for (let j = 0; j < 2; j++) {
            const angle2 = (j / 2) * Math.PI * 2 + Math.random() * 0.5;
            const np = n_level1.position;
            const n_level2 = mindMap.addNode(new NoteNode(null, {
                x: np.x + Math.cos(angle2) * 80,
                y: np.y + Math.sin(angle2) * 80,
                z: np.z + (Math.random() - 0.5) * 30
            }, {content: `L2-${j + 1}`, width: 60, height: 30, backgroundColor: colors[2]}));
            mindMap.addEdge(n_level1, n_level2);
        }
    }
}

try {
    init();
} catch (error) {
    console.error("Initialization Failed:", error);
}