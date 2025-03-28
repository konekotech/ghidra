/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.gui.model;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ghidra.trace.model.Lifespan;
import ghidra.trace.model.Trace;
import ghidra.trace.model.target.*;
import ghidra.trace.model.target.path.*;
import ghidra.trace.model.target.schema.PrimitiveTraceObjectSchema;
import ghidra.trace.model.target.schema.TraceObjectSchema;
import ghidra.trace.model.target.schema.TraceObjectSchema.AttributeSchema;

public class ModelQuery {
	public static final ModelQuery EMPTY = new ModelQuery(PathFilter.NONE);
	// TODO: A more capable query language, e.g., with WHERE clauses.
	// Could also want math expressions for the conditionals... Hmm.
	// They need to be user enterable, so just a Java API won't suffice.

	public static ModelQuery parse(String queryString) {
		return new ModelQuery(PathFilter.parse(queryString));
	}

	public static ModelQuery elementsOf(KeyPath path) {
		return new ModelQuery(new PathPattern(path.index("")));
	}

	public static ModelQuery attributesOf(KeyPath path) {
		return new ModelQuery(new PathPattern(path.key("")));
	}

	private final PathFilter filter;

	/**
	 * TODO: This should probably be more capable, but for now, just support simple path patterns
	 * 
	 * @param filter the filter
	 */
	public ModelQuery(PathFilter filter) {
		this.filter = filter;
	}

	@Override
	public String toString() {
		return "<ModelQuery: " + filter.toString() + ">";
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		if (!(obj instanceof ModelQuery)) {
			return false;
		}
		ModelQuery that = (ModelQuery) obj;
		if (!Objects.equals(this.filter, that.filter)) {
			return false;
		}
		return true;
	}

	/**
	 * Render the query as a string as in {@link #parse(String)}
	 * 
	 * @return the string
	 */
	public String toQueryString() {
		return filter.getSingletonPattern().toPatternString();
	}

	/**
	 * Execute the query
	 * 
	 * @param trace the data source
	 * @param span the span of snapshots to search, usually all or a singleton
	 * @return the stream of resulting objects
	 */
	public Stream<TraceObject> streamObjects(Trace trace, Lifespan span) {
		TraceObjectManager objects = trace.getObjectManager();
		TraceObject root = objects.getRootObject();
		return objects.getValuePaths(span, filter)
				.map(p -> p.getDestinationValue(root))
				.filter(v -> v instanceof TraceObject)
				.map(v -> (TraceObject) v);
	}

	public Stream<TraceObjectValue> streamValues(Trace trace, Lifespan span) {
		TraceObjectManager objects = trace.getObjectManager();
		return objects.getValuePaths(span, filter).map(p -> {
			TraceObjectValue last = p.getLastEntry();
			return last == null ? objects.getRootObject().getCanonicalParent(0) : last;
		});
	}

	public Stream<TraceObjectValPath> streamPaths(Trace trace, Lifespan span) {
		return trace.getObjectManager().getValuePaths(span, filter).map(p -> p);
	}

	public List<TraceObjectSchema> computeSchemas(Trace trace) {
		TraceObjectSchema rootSchema = trace.getObjectManager().getRootSchema();
		if (rootSchema == null) {
			return List.of();
		}
		return filter.getPatterns()
				.stream()
				.map(p -> rootSchema.getSuccessorSchema(p.asPath()))
				.distinct()
				.collect(Collectors.toList());
	}

	public TraceObjectSchema computeSingleSchema(Trace trace) {
		List<TraceObjectSchema> schemas = computeSchemas(trace);
		if (schemas.size() != 1) {
			return PrimitiveTraceObjectSchema.OBJECT;
		}
		return schemas.get(0);
	}

	/**
	 * Compute the named attributes for resulting objects, according to the schema
	 * 
	 * <p>
	 * This does not include the "default attribute schema."
	 * 
	 * @param trace the data source
	 * @return the list of attributes
	 */
	public Stream<AttributeSchema> computeAttributes(Trace trace) {
		TraceObjectSchema schema = computeSingleSchema(trace);
		return schema.getAttributeSchemas()
				.entrySet()
				.stream()
				.filter(ent -> {
					String attrName = ent.getValue().getName();
					return !"".equals(attrName) && ent.getKey().equals(attrName);
				})
				.map(e -> e.getValue());
	}

	protected static boolean includes(Lifespan span, PathPattern pattern, TraceObjectValue value) {
		KeyPath asPath = pattern.asPath();
		if (asPath.isRoot()) {
			// If the pattern is the root, then only match the "root value"
			return value.getParent() == null;
		}
		if (!PathFilter.keyMatches(asPath.key(), value.getEntryKey())) {
			return false;
		}
		TraceObject parent = value.getParent();
		if (parent == null) {
			// Value is the root. We would already have matched above
			return false;
		}
		return parent.getAncestors(span, pattern.removeRight(1))
				.anyMatch(v -> v.getSource(parent).isRoot());
	}

	/**
	 * Determine whether this query would include the given value in its result
	 * 
	 * <p>
	 * More precisely, determine whether it would traverse the given value, accept it, and include
	 * its child in the result. It's possible the child could be included via another value, but
	 * this only considers the given value.
	 * 
	 * @param span the span to consider
	 * @param value the value to examine
	 * @return true if the value would be accepted
	 */
	public boolean includes(Lifespan span, TraceObjectValue value) {
		if (!span.intersects(value.getLifespan())) {
			return false;
		}
		for (PathPattern pattern : filter.getPatterns()) {
			if (includes(span, pattern, value)) {
				return true;
			}
		}
		return false;
	}

	protected static boolean involves(Lifespan span, PathPattern pattern, TraceObjectValue value) {
		TraceObject parent = value.getParent();
		// Every query involves the root
		if (parent == null) {
			return true;
		}

		// Check if any of the value's paths could be an ancestor of a result
		KeyPath asPath = pattern.asPath();
		// Destroy the pattern from the right, thus iterating each ancestor
		while (!asPath.isRoot()) {
			// The value's key much match somewhere in the pattern to be involved
			if (!PathFilter.keyMatches(asPath.key(), value.getEntryKey())) {
				asPath = asPath.parent();
				continue;
			}
			// If it does, then check if any path to the value's parent matches the rest
			asPath = asPath.parent();
			if (parent.getAncestors(span, new PathPattern(asPath))
					.anyMatch(v -> v.getSource(parent).isRoot())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether the query results could depend on the given value
	 * 
	 * @param span the lifespan of interest, e.g., the span being displayed
	 * @param value the value that has changed
	 * @return true if the query results depend on the given value
	 */
	public boolean involves(Lifespan span, TraceObjectValue value) {
		if (!span.intersects(value.getLifespan())) {
			return false;
		}
		for (PathPattern pattern : filter.getPatterns()) {
			if (involves(span, pattern, value)) {
				return true;
			}
		}
		return false;
	}

	public boolean isEmpty() {
		return filter.isNone();
	}
}
