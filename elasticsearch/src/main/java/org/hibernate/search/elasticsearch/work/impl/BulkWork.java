/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.elasticsearch.work.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.elasticsearch.client.Response;
import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.elasticsearch.client.impl.ElasticsearchRequest;
import org.hibernate.search.elasticsearch.gson.impl.GsonProvider;
import org.hibernate.search.elasticsearch.logging.impl.Log;
import org.hibernate.search.elasticsearch.util.impl.ElasticsearchClientUtils;
import org.hibernate.search.elasticsearch.work.impl.builder.BulkWorkBuilder;
import org.hibernate.search.exception.SearchException;
import org.hibernate.search.util.impl.CollectionHelper;
import org.hibernate.search.util.logging.impl.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * @author Yoann Rodiere
 */
public class BulkWork implements ElasticsearchWork<Void> {

	private static final Log LOG = LoggerFactory.make( Log.class );

	private final ElasticsearchRequest request;

	private final List<BulkableElasticsearchWork<?>> works;

	/**
	 * Whether to perform a refresh in the course of executing this bulk or not.
	 * <p>
	 * Note that this will refresh all indexes touched by this bulk,
	 * not only those given via {@link #indexesNeedingRefresh}. That's acceptable.
	 * <p>
	 * If {@code true}, no additional refresh of the concerned indexes
	 * is needed after executing the bulk.
	 */
	private final boolean refreshInAPICall;

	protected BulkWork(Builder builder) {
		super();
		this.request = builder.buildRequest();
		this.works = new ArrayList<>( builder.bulkableWorks );
		this.refreshInAPICall = builder.refreshInBulkAPICall;
	}

	@Override
	public String toString() {
		return new StringBuilder()
				.append( getClass().getSimpleName() )
				.append( "[" )
				.append( "works = " ).append( works )
				.append( ", refreshInAPICall = " ).append( refreshInAPICall )
				.append( "]" )
				.toString();
	}

	@Override
	public Void execute(ElasticsearchWorkExecutionContext context) {
		if ( refreshInAPICall ) {
			/*
			 * Prevent bulked works to mark indexes as dirty,
			 * since we refresh all indexes as part of the Bulk API call.
			 */
			context = new NoIndexDirtyBulkExecutionContext( context );
		}

		GsonProvider gsonProvider = context.getGsonProvider();

		Response response = null;
		JsonObject parsedResponseBody = null;
		try {
			response = context.getClient().execute( request );
			parsedResponseBody = ElasticsearchClientUtils.parseJsonResponse( gsonProvider, response );

			handleResults( context, response, parsedResponseBody );

			return null;
		}
		catch (SearchException e) {
			throw e; // Do not add context for those: we expect SearchExceptions to be self-explanatory
		}
		catch (IOException | RuntimeException e) {
			throw LOG.elasticsearchRequestFailed(
					ElasticsearchClientUtils.formatRequest( gsonProvider, request ),
					ElasticsearchClientUtils.formatResponse( gsonProvider, response, parsedResponseBody ),
					e );
		}
	}

	@Override
	public void aggregate(ElasticsearchWorkAggregator aggregator) {
		aggregator.addNonBulkable( this );
	}

	@Override
	public Stream<LuceneWork> getLuceneWorks() {
		Stream<LuceneWork> result = Stream.empty();
		for ( BulkableElasticsearchWork<?> work : works ) {
			result = Stream.concat( result, work.getLuceneWorks() );
		}
		return result;
	}

	/*
	 * Give the chance for every work to handle the result,
	 * making sure that exceptions are handled properly
	 * so that one failing handler will not prevent others from being called.
	 *
	 * If at least one work or its result handler failed,
	 * an exception will be thrown after every result has been handled.
	 */
	private void handleResults(ElasticsearchWorkExecutionContext context, Response response, JsonObject parsedResponseBody) {
		Map<BulkableElasticsearchWork<?>, JsonObject> successfulItems =
				CollectionHelper.newHashMap( works.size() );

		List<BulkableElasticsearchWork<?>> erroneousItems = new ArrayList<>();
		int i = 0;

		JsonArray resultItems = parsedResponseBody.has( "items" ) ? parsedResponseBody.get( "items" ).getAsJsonArray() : null;

		List<RuntimeException> resultHandlingExceptions = null;
		for ( BulkableElasticsearchWork<?> work : works ) {
			JsonObject resultItem = resultItems != null ? resultItems.get( i ).getAsJsonObject() : null;

			boolean success;
			try {
				success = work.handleBulkResult( context, resultItem );
			}
			catch (RuntimeException e) {
				if ( resultHandlingExceptions == null ) {
					resultHandlingExceptions = new ArrayList<>();
				}
				resultHandlingExceptions.add( e );
				success = false;
			}

			if ( success ) {
				successfulItems.put( work, resultItem );
			}
			else {
				erroneousItems.add( work );
			}

			++i;
		}

		if ( !erroneousItems.isEmpty() ) {
			GsonProvider gsonProvider = context.getGsonProvider();
			BulkRequestFailedException exception = LOG.elasticsearchBulkRequestFailed(
					ElasticsearchClientUtils.formatRequest( gsonProvider, request ),
					ElasticsearchClientUtils.formatResponse( gsonProvider, response, parsedResponseBody ),
					successfulItems,
					erroneousItems
			);
			if ( resultHandlingExceptions != null ) {
				for ( Exception resultHandlingException : resultHandlingExceptions ) {
					exception.addSuppressed( resultHandlingException );
				}
			}
			throw exception;
		}
	}

	private static class NoIndexDirtyBulkExecutionContext extends ForwardingElasticsearchWorkExecutionContext {

		public NoIndexDirtyBulkExecutionContext(ElasticsearchWorkExecutionContext delegate) {
			super( delegate );
		}

		@Override
		public void setIndexDirty(String indexName) {
			// Don't delegate
		}
	}

	public static class Builder implements BulkWorkBuilder {
		private final List<BulkableElasticsearchWork<?>> bulkableWorks;
		private boolean refreshInBulkAPICall;

		public Builder(List<BulkableElasticsearchWork<?>> bulkableWorks) {
			this.bulkableWorks = bulkableWorks;
		}

		@Override
		public Builder refresh(boolean refresh) {
			this.refreshInBulkAPICall = refresh;
			return this;
		}

		protected ElasticsearchRequest buildRequest() {
			ElasticsearchRequest.Builder builder =
					ElasticsearchRequest.post()
					.pathComponent( "_bulk" )
					.param( "refresh", refreshInBulkAPICall );

			for ( BulkableElasticsearchWork<?> work : bulkableWorks ) {
				builder.body( work.getBulkableActionMetadata() );
				JsonObject actionBody = work.getBulkableActionBody();
				if ( actionBody != null ) {
					builder.body( actionBody );
				}
			}

			return builder.build();
		}

		@Override
		public BulkWork build() {
			return new BulkWork( this );
		}
	}
}
