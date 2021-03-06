package easy.framework.mvc.impl;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easy.framework.InstanceFactory;
import easy.framework.ioc.BeanHelper;
import easy.framework.mvc.HandlerInvoke;
import easy.framework.mvc.HandlerViewResolver;
import easy.framework.mvc.annotation.FileBody;
import easy.framework.mvc.annotation.Param;
import easy.framework.mvc.annotation.RequestBody;
import easy.framework.mvc.helper.FileUploadHelper;
import easy.framework.mvc.model.FileModel;
import easy.framework.mvc.model.ParamModel;
import easy.framework.mvc.model.RequestHandler;
import easy.framework.mvc.model.RequestParamModel;
import easy.framework.utils.JsonUtils;
import easy.framework.utils.ReflectUtils;
import easy.framework.utils.ServletUtils;

/**
 * @author limengyu
 * @create 2017/09/19
 */
public class DefaultHandlerInvoke implements HandlerInvoke {
	private static final Logger logger = LoggerFactory.getLogger(DefaultHandlerInvoke.class);

	/**
	 * @param request
	 * @param response
	 * @param requestHandler
	 */
	@Override
	public void invoke(HttpServletRequest request, HttpServletResponse response, RequestHandler requestHandler) throws Exception {
		logger.debug("***********请求开始************");
		Method method = requestHandler.getMethod();
		Object beanInstance = BeanHelper.getBeanInstance(requestHandler.getControllerClass());
		Object[] params = this.builderMethodParam(request, requestHandler);
		Object resultObj = method.invoke(beanInstance, params);
		logger.debug("[easy-mvc]请求执行结果: {}", JsonUtils.toJson(resultObj));
		logger.debug("***********请求结束************");
		HandlerViewResolver viewResolver = InstanceFactory.getHandlerViewResolver();
		viewResolver.resolver(request, response, resultObj);
	}
	/**
	 * 构造方法执行参数
	 * @param request
	 * @param requestHandler
	 * @return objects
	 */
	private Object[] builderMethodParam(HttpServletRequest request, RequestHandler requestHandler) throws Exception{
		Method method = requestHandler.getMethod();
		List<String> methodParamNameList = ReflectUtils.findMethodParamName(method);
		logger.debug("[easy-mvc]方法参数列表: {},{}", method.getName(), methodParamNameList);
		String requestPath = ServletUtils.requestPath(request);
		List<ParamModel> paramList = this.getMethodParamInfo(method, methodParamNameList);
		List<ParamModel> requestBodyList = paramList.stream().filter(model -> model.isRequestBody()).collect(Collectors.toList());
		//开始获取参数
		RequestParamModel requestParamModel = FileUploadHelper.parseFormParam(request);
		Map<String, List<String>> formParamsMap = requestParamModel.getFormFileMap();
		List<FileModel> fileList = requestParamModel.getFileList();
		Map<String, Object> jsonParamsMap = ServletUtils.parseJsonParams(request, requestBodyList);
		Map<String, List<String>> parameterParamsMap = ServletUtils.parseParameterParams(request);
		Map<String, String> kvParamsMap = ServletUtils.parseQueryParams(request);
		Map<String, String> pathParamsMap = this.parsePathParams(requestHandler, requestPath);

		List<Object> paramValueList = new ArrayList<>();
		if (paramList != null && paramList.size() > 0) {
			for (ParamModel paramModel : paramList) {
				String paramName = paramModel.getParamName();
				Object paramValue = paramModel.getParamValue();
				if (pathParamsMap.containsKey(paramName)) {
					paramValue = pathParamsMap.get(paramName);
				} else if (kvParamsMap.containsKey(paramName)) {
					paramValue = kvParamsMap.get(paramName);
				} else if (jsonParamsMap.containsKey(paramName)) {
					paramValue = jsonParamsMap.get(paramName);
				} else if (formParamsMap != null && formParamsMap.containsKey(paramName)) {
					paramValue = formParamsMap.get(paramName);
				} else if (parameterParamsMap.containsKey(paramName)) {
					paramValue = parameterParamsMap.get(paramName);
				} else if (paramModel.isFileBody() && fileList != null && fileList.size() > 0) {
					if (List.class.isAssignableFrom(paramModel.getParamType())) {
						paramValue = fileList;
					} else {
						paramValue = fileList.get(0);
					}
				}
				paramModel.setParamValue(paramValue);
				paramValueList.add(ReflectUtils.convertValue(paramModel.getParamType(), paramValue));
			}
		}
		Object[] objects = paramValueList.toArray();
		return objects;
	}
	private List<ParamModel> getMethodParamInfo(Method method, List<String> methodParamName) {
		List<ParamModel> paramList = new ArrayList<>();
		if (methodParamName == null || methodParamName.size() == 0) {
			return paramList;
		}
		Parameter[] parameters = method.getParameters();
		ParamModel paramModel;
		for (int i = 0; i < parameters.length; i++) {
			paramModel = new ParamModel();
			paramModel.setParamName(methodParamName.get(i));
			paramModel.setParamType(parameters[i].getType());
			paramModel.setParamIndex(i);
			if (parameters[i].isAnnotationPresent(Param.class)) {
				String defaultValue = parameters[i].getAnnotation(Param.class).defaultValue();
				paramModel.setParamValue(defaultValue);
			}
			if (parameters[i].isAnnotationPresent(RequestBody.class)) {
				paramModel.setRequestBody(true);
			}
			if (parameters[i].isAnnotationPresent(FileBody.class)) {
				paramModel.setFileBody(true);
			}
			paramList.add(paramModel);
		}
		return paramList;
	}
	private Map<String, String> parsePathParams(RequestHandler requestHandler, String requestPath) {
		List<String> pathParams = requestHandler.getPathParams();
		List<String> pathParamsValue = requestHandler.matchGroup(requestPath);
		Map<String, String> params = new HashMap<>(16);
		if (pathParams == null || pathParamsValue == null) {
			return params;
		}
		if (pathParams.size() != pathParamsValue.size()) {
			throw new RuntimeException("path参数不匹配");
		}
		for (int i = 0; i < pathParams.size(); i++) {
			params.put(pathParams.get(i), pathParamsValue.get(i));
		}
		return params;
	}
	public void checkParamCount(Map<String, String> pathParamsMap, Map<String, String> kvParams, int parameterCount) {
		int pathNum = pathParamsMap.keySet().stream().mapToInt(key -> 1).sum();
		int kvNum = kvParams.keySet().stream().mapToInt(key -> 1).sum();
		if (pathNum + kvNum != parameterCount) {
			logger.debug("path参数个数: {},kv参数个数: {},方法参数总数: {}", pathNum, kvNum, parameterCount);
			throw new RuntimeException("参数缺失");
		}
	}
}
