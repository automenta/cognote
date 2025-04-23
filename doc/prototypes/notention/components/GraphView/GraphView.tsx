import React, {useRef, useEffect, useState, useCallback} from 'react';
import * as d3 from 'd3';
import styles from './GraphView.module.css';
import {Note} from '../../types';
import {useSystemNote} from '../../lib/systemNote';

interface Node {
    id: string;
    title: string;
    type: string; // Add type property
    x: number;
    y: number;
}

interface Edge {
    source: string;
    target: string;
}

const GraphView: React.FC<{ selectedNoteId: string | null }> = ({selectedNoteId}) => {
    const svgRef = useRef<SVGSVGElement>(null);
    const container = useRef<HTMLDivElement>(null);
    const zoom = useRef<any>(null);
    const [nodes, setNodes] = useState<Node[]>([]);
    const [edges, setEdges] = useState<Edge[]>([]);
    const systemNote = useSystemNote();

    const updateGraph = useCallback(async () => {
        if (!systemNote) return;

        const allNotes = await systemNote.getAllNotes();
        const graphNodes: Node[] = allNotes.map(note => ({
            id: note.id,
            title: note.title,
            type: note.type,
            x: Math.random() * 300,
            y: Math.random() * 300,
        }));

        const graphEdges: Edge[] = allNotes.flatMap(note =>
            note.references.map(target => ({
                source: note.id,
                target: target,
            }))
        );

        setNodes(graphNodes);
        setEdges(graphEdges);
    }, [systemNote]);

    useEffect(() => {
        updateGraph();
    }, [updateGraph]);

    useEffect(() => {
        if (!nodes.length || !edges.length || !svgRef.current) return;

        try {
            const svg = d3.select(svgRef.current);
            svg.selectAll('*').remove();  // Clear previous graph

            // Initialize zoom behavior
            zoom.current = d3.zoom()
                .scaleExtent([0.1, 3]) // Limit zoom scale
                .on("zoom", (event) => {
                    container.current!.attr("transform", event.transform);
                });

            svg.call(zoom.current);

            // Create a container for the graph elements
            container.current = svg.append("g");

            // Initialize the simulation
            const simulation = d3.forceSimulation(nodes as any)
                .force("link", d3.forceLink(edges).id((d: any) => d.id))
                .force("charge", d3.forceManyBody().strength(-50))
                .force("center", d3.forceCenter(svgRef.current.clientWidth / 2, svgRef.current.clientHeight / 2));

            // Create links
            const link = container.current!.append("g")
                .attr("stroke", "#999")
                .attr("stroke-opacity", 0.6)
                .selectAll("line")
                .data(edges)
                .join("line")
                .attr("marker-end", "url(#arrowhead)");

            // Define arrowhead marker
            svg.append("defs").append("marker")
                .attr("id", "arrowhead")
                .attr("viewBox", "0 -5 10 10")
                .attr("refX", 15)
                .attr("refY", 0)
                .attr("orient", "auto")
                .attr("markerWidth", 6)
                .attr("markerHeight", 6)
                .attr("xoverflow", "visible")
                .append("path")
                .attr("d", "M0,-5L10,0L0,5")
                .attr("fill", "#999");

            // Create nodes
            const node = container.current!.append("g")
                .attr("stroke", "#fff")
                .attr("stroke-width", 1.5)
                .selectAll("circle")
                .data(nodes)
                .join("circle")
                .attr("r", 10)
                .attr("fill", (d: any) => {
                    switch (d.type) {
                        case 'Task':
                            return "#69b3a2";
                        case 'Tool':
                            return "#4287f5";
                        case 'System':
                            return "#f00";
                        default:
                            return "#ccc";
                    }
                })
                .call(d3.drag()
                    .on("start", dragstarted)
                    .on("drag", dragged)
                    .on("end", dragended) as any);

            // Add labels to the nodes
            const labels = container.current!.append("g")
                .selectAll("text")
                .data(nodes)
                .join("text")
                .text((d: any) => d.title)
                .style("text-anchor", "middle")
                .style("font-size", "10px")
                .style("fill", "black")
                .attr("dy", 15); // Position the labels below the nodes

            simulation.on("tick", () => {
                link
                    .attr("x1", (d: any) => d.source.x)
                    .attr("y1", (d: any) => d.source.y)
                    .attr("x2", (d: any) => d.target.x)
                    .attr("y2", (d: any) => d.target.y);

                node
                    .attr("cx", (d: any) => d.x)
                    .attr("cy", (d: any) => d.y);

                labels
                    .attr("x", (d: any) => d.x)
                    .attr("y", (d: any) => d.y);
            });

            function dragstarted(event: any, d: any) {
                if (!event.active) simulation.alphaTarget(0.3).restart();
                d.fx = d.x;
                d.fy = d.y;
            }

            function dragged(event: any, d: any) {
                d.fx = event.x;
                d.fy = event.y;
            }

            function dragended(event: any, d: any) {
                if (!event.active) simulation.alphaTarget(0);
                d.fx = null;
                d.fy = d.y;
            }

        } catch (e) {
            console.error('error in graph', e);
        }

    }, [nodes, edges]);

    return (
        <div className={styles.graphView}>
            <h2>Task Graph</h2>
            <svg ref={svgRef} width="100%" height="600px"></svg>
        </div>
    );
};
