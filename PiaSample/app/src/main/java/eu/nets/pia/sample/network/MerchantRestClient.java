package eu.nets.pia.sample.network;

import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;

import eu.nets.pia.sample.RegisterPaymentHandlerImpl;
import eu.nets.pia.sample.data.PaymentFlowCache;
import eu.nets.pia.sample.data.PaymentFlowState;
import eu.nets.pia.sample.data.PiaSampleSharedPreferences;
import eu.nets.pia.sample.network.model.ErrorResponse;
import eu.nets.pia.sample.network.model.PaymentCommitResponse;
import eu.nets.pia.sample.network.model.PaymentMethodsResponse;
import eu.nets.pia.sample.network.model.PaymentRegisterRequest;
import eu.nets.pia.sample.network.model.PaymentRegisterResponse;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

/**
 * MIT License
 * <p>
 * Copyright (c) 2019 Nets Denmark A/S
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy  of this software
 * and associated documentation files (the "Software"), to deal  in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is  furnished to do so,
 * subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

public class MerchantRestClient {

    private static final String TAG = MerchantRestClient.class.getSimpleName();

    private static MerchantRestClient mInstance;

    private Gson mGson;
    private PaymentFlowCache mPaymentCache;

    public interface PaymentFlowCallback {
        void onPaymentCallFinished();

        void onGetPaymentMethods(PaymentMethodsResponse response);
    }

    protected final LinkedBlockingQueue<PaymentFlowCallback> mPaymentFlowListeners;
    private MerchantBackendAPI mMerchantBackendAPI;

    public static MerchantRestClient getInstance() {
        if (mInstance == null) {
            mInstance = new MerchantRestClient();
        }
        return mInstance;
    }

    private MerchantRestClient() {
        //handle Base Url
        String BASE_URL = getBaseUrl(PiaSampleSharedPreferences.isPiaTestMode());

        Retrofit retrofit = new Retrofit.Builder()
                .addConverterFactory(GsonConverterFactory.create(new GsonBuilder().serializeNulls().create()))
//            .addConverterFactory(ScalarsConverterFactory.create())
                .baseUrl(BASE_URL)
                .build();

        mMerchantBackendAPI = retrofit.create(MerchantBackendAPI.class);

        mGson = new Gson();
        mPaymentCache = PaymentFlowCache.getInstance();

        mPaymentFlowListeners = new LinkedBlockingQueue();
    }

    private String getBaseUrl(boolean isTestMode) {
        //return the url that user has set if is not null ; else return default url
        if (isTestMode) {
            return PiaSampleSharedPreferences.getMerchantEnvTest();
        } else {
            return PiaSampleSharedPreferences.getMerchantEnvProd();
        }
    }

    public static void notifyBackendConfigurationChanged() {
        //User switched between PROD and TEST environments -- recreate the Rest Client
        mInstance.recreateClient();
    }

    //recreate just the backend configuration to keep all flow listeners stored
    private void recreateClient() {
        String BASE_URL = getBaseUrl(PiaSampleSharedPreferences.isPiaTestMode());

        Retrofit retrofit = new Retrofit.Builder()
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl(BASE_URL)
                .build();

        mMerchantBackendAPI = retrofit.create(MerchantBackendAPI.class);
    }

    private String getMerchantId() {
        if (PiaSampleSharedPreferences.isPiaTestMode()) {
            return PiaSampleSharedPreferences.getMerchantIdTest();
        } else {
            return PiaSampleSharedPreferences.getMerchantIdProd();
        }
    }

    /**
     * Call registerPayment to your backend synchronously
     * This method is called by the {@link RegisterPaymentHandlerImpl}
     * On the success case, store the register response in the payment cache to be retrieved later
     * In error case, the register response from the cache will be null, so SDK will be notified that TransactionInfo
     * object is null - the payment won't go through
     *
     * @param paymentRegisterRequest -
     */
    public void registerPayment(PaymentRegisterRequest paymentRegisterRequest) {
        Log.d(TAG, "[registerPayment] [paymentRegisterRequest:" + paymentRegisterRequest.toString() + " merchantId:" + getMerchantId() + "]");
        mPaymentCache.setState(PaymentFlowState.SENDING_REGISTER_PAYMENT_CALL);
        try {
            Response<PaymentRegisterResponse> response = mMerchantBackendAPI.registerPayment(paymentRegisterRequest, getMerchantId()).execute();
            mPaymentCache.setState(PaymentFlowState.REGISTER_PAYMENT_CALL_FINISHED);
            if (response.isSuccessful()) {
                Log.d(TAG, "[onResponse] [response.body():" + response.body() != null ? response.body().toString() : "" + "]");
                mPaymentCache.setPaymentRegisterResponse(response.body());
                mPaymentCache.setFinishedWithError(false);
            } else {
                parseErrorResponse(response.errorBody());
            }
            notifyPaymentFlowEvents();
        } catch (IOException e) {
            Log.e(TAG, "[onFailure] [throwable:" + e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + "]");
            mPaymentCache.setState(PaymentFlowState.REGISTER_PAYMENT_CALL_FINISHED);
            mPaymentCache.setFinishedWithError(true);
            notifyPaymentFlowEvents();
        }
    }

    /**
     * After SDK notifies your application that the transaction was successful, call your backend
     * to commit the payment (capture the payment)
     *
     * @param transactionId The ID of the transaction
     */
    public void commitPayment(String transactionId) {
        Log.d(TAG, "[commitPayment] [transactionId:" + transactionId + " merchantId:" + getMerchantId() + "]");
        mPaymentCache.setState(PaymentFlowState.SENDING_COMMIT_PAYMENT_CALL);
        mMerchantBackendAPI.commitPayment(transactionId, getMerchantId(), "{}").enqueue(new Callback<PaymentCommitResponse>() {
            @Override
            public void onResponse(Call<PaymentCommitResponse> call, Response<PaymentCommitResponse> response) {
                mPaymentCache.setState(PaymentFlowState.COMMIT_PAYMENT_CALL_FINISHED);
                if (response.isSuccessful()) {
                    Log.d(TAG, "[onResponse] [response.body():" + response.body() != null ? response.body().toString() : "" + "]");
                    mPaymentCache.setPaymentCommitResponse(response.body());
                    mPaymentCache.setFinishedWithError(false);
                } else {
                    parseErrorResponse(response.errorBody());
                }
                notifyPaymentFlowEvents();
            }

            @Override
            public void onFailure(Call<PaymentCommitResponse> call, Throwable t) {
                Log.e(TAG, "[onFailure] [throwable:" + t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName() + "]");
                mPaymentCache.setState(PaymentFlowState.COMMIT_PAYMENT_CALL_FINISHED);
                mPaymentCache.setFinishedWithError(true);
                notifyPaymentFlowEvents();
            }
        });
    }

    /**
     * In the save card functionality, after SDK notifies your application that the terminal call was successful,
     * call your backend to store the card
     *
     * @param transactionId The id of the transaction
     */
    public void verifyPayment(String transactionId) {
        Log.d(TAG, "[verifyPayment] [transactionId:" + transactionId + " merchantId:" + getMerchantId() + "]");
        mPaymentCache.setState(PaymentFlowState.SENDING_COMMIT_PAYMENT_CALL);
        mMerchantBackendAPI.verifyPayment(transactionId, getMerchantId(), "{}").enqueue(new Callback<PaymentCommitResponse>() {
            @Override
            public void onResponse(Call<PaymentCommitResponse> call, Response<PaymentCommitResponse> response) {
                mPaymentCache.setState(PaymentFlowState.COMMIT_PAYMENT_CALL_FINISHED);
                if (response.isSuccessful()) {
                    Log.d(TAG, "[onResponse] [response.body():" + response.body() != null ? response.body().toString() : "" + "]");
                    mPaymentCache.setPaymentCommitResponse(response.body());
                    mPaymentCache.setFinishedWithError(false);
                } else {
                    parseErrorResponse(response.errorBody());
                }
                notifyPaymentFlowEvents();
            }

            @Override
            public void onFailure(Call<PaymentCommitResponse> call, Throwable t) {
                Log.e(TAG, "[onFailure] [throwable:" + t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName() + "]");
                mPaymentCache.setState(PaymentFlowState.COMMIT_PAYMENT_CALL_FINISHED);
                mPaymentCache.setFinishedWithError(true);
                notifyPaymentFlowEvents();
            }
        });
    }

    /**
     * Retrieve from your backend the available payment methods for the specific customer
     *
     * @param consumerId - the id of the customer
     */
    public void getPaymentMethods(String consumerId) {
        Log.d(TAG, "[getPaymentMethods] [consumerId=" + consumerId + "]");
        mMerchantBackendAPI.getPaymentMethods(consumerId).enqueue(new Callback<PaymentMethodsResponse>() {
            @Override
            public void onResponse(Call<PaymentMethodsResponse> call, Response<PaymentMethodsResponse> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "[onResponse] [response.body():" + response.body() != null ? response.body().toString() : "" + "]");
                    notifyGetPaymentMethodsEvent(response.body());
                } else {
                    parseErrorResponse(response.errorBody());
                    //for bad URLs or network errors handle response on app with popup
                    mPaymentCache.setFailedRequest(true);
                    notifyPaymentFlowEvents();
                }
            }

            @Override
            public void onFailure(Call<PaymentMethodsResponse> call, Throwable t) {
                Log.e(TAG, "[onFailure] [throwable:" + t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName() + "]");
                mPaymentCache.setFailedRequest(true);
                notifyPaymentFlowEvents();
            }
        });
    }

    /**
     * In case the SDK notifies your application that the user canceled the transaction, or
     * there was an error processing the payment, you must call this API to your backend to
     * rollback the transaction
     *
     * @param transactionId The ID of the transaction
     */
    public void transactionRollback(String transactionId) {
        Log.d(TAG, "[transactionRollback] [transactionId:" + transactionId + " merchantId:" + getMerchantId() + "]");
        mPaymentCache.setState(PaymentFlowState.SENDING_ROLLBACK_TRANSACTION_CALL);
        mMerchantBackendAPI.transactionRollback(transactionId, getMerchantId()).enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                mPaymentCache.setState(PaymentFlowState.ROLLBACK_TRANSACTION_FINISHED);
                if (response.isSuccessful()) {
                    Log.d(TAG, "[onResponse]");
                    mPaymentCache.setFinishedWithError(false);
                } else {
                    parseErrorResponse(response.errorBody());
                }
                notifyPaymentFlowEvents();
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "[onFailure] [throwable:" + t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName() + "]");
                mPaymentCache.setState(PaymentFlowState.ROLLBACK_TRANSACTION_FINISHED);
                mPaymentCache.setFinishedWithError(true);
                notifyPaymentFlowEvents();
            }
        });
    }

    private void parseErrorResponse(ResponseBody errorBody) {
        try {
            Log.d(TAG, "[onResponse] [response.errorBody():" + errorBody != null ? errorBody.string() : "" + "]");
            mPaymentCache.setFinishedWithError(true);
            if (errorBody != null && !errorBody.string().isEmpty()) {
                ErrorResponse errorResponse = mGson.fromJson(errorBody.string(), ErrorResponse.class);
                if (errorResponse != null && errorResponse.getExplanationText() != null) {
                    Log.e(TAG, errorResponse.getExplanationText() + ": " +
                            (errorResponse.getParams() != null ? errorResponse.getParams().getMessage() : ""));
                } else {
                    Log.e(TAG, errorBody.string());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public void addListener(PaymentFlowCallback listener) {
        if (!mPaymentFlowListeners.contains(listener)) {
            mPaymentFlowListeners.add(listener);
        }
    }

    public void removeListener(PaymentFlowCallback listener) {
        if (mPaymentFlowListeners.contains(listener)) {
            mPaymentFlowListeners.remove(listener);
        }
    }

    private void notifyPaymentFlowEvents() {
        for (PaymentFlowCallback callback : mPaymentFlowListeners) {
            callback.onPaymentCallFinished();
        }
    }

    private void notifyGetPaymentMethodsEvent(PaymentMethodsResponse paymentMethodsResponse) {
        for (PaymentFlowCallback callback : mPaymentFlowListeners) {
            callback.onGetPaymentMethods(paymentMethodsResponse);
        }
    }

}
