package org.visallo.web.routes.product;

import com.google.inject.Inject;
import com.v5analytics.webster.ParameterizedHandler;
import com.v5analytics.webster.annotations.Handle;
import com.v5analytics.webster.annotations.Optional;
import org.json.JSONObject;
import org.visallo.core.model.workspace.WorkspaceRepository;
import org.visallo.core.model.workspace.product.Product;
import org.visallo.core.user.User;
import org.visallo.core.util.ClientApiConverter;
import org.visallo.web.clientapi.model.ClientApiProduct;
import org.visallo.web.parameterProviders.ActiveWorkspaceId;

public class ProductUpdate implements ParameterizedHandler {
    private final WorkspaceRepository workspaceRepository;

    @Inject
    public ProductUpdate(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Handle
    public ClientApiProduct handle(
            @Optional(name = "productId") String productId,
            @Optional(name = "title") String title,
            @Optional(name = "kind") String kind,
            @Optional(name = "params") String paramsStr,
            @ActiveWorkspaceId String workspaceId,
            User user
    ) throws Exception {
        JSONObject params;
        if (paramsStr == null) {
            params = new JSONObject();
        } else {
            params = new JSONObject(paramsStr);
        }
        Product product = workspaceRepository.addOrUpdateProduct(workspaceId, productId, title, kind, params, user);
        return ClientApiConverter.toClientApiProduct(product);
    }
}